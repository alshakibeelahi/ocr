package com.ocr.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocr.application.dto.OcrJobResponse;
import com.ocr.application.port.JobCallbackPort;
import com.ocr.application.port.OllamaVisionPort;
import com.ocr.application.port.StreamEventPublisher;
import com.ocr.domain.model.JobId;
import com.ocr.domain.model.OcrJob;
import com.ocr.domain.model.PageMetadata;
import com.ocr.domain.repository.OcrJobRepository;
import com.ocr.domain.service.ImagePreprocessor;
import com.ocr.domain.service.PdfPageExtractor;
import com.ocr.interfaces.config.OcrProperties;
import com.ocr.interfaces.config.OllamaProperties;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Drives one extraction end to end with the model and the PDF renderer stubbed out.
 *
 * <p>The two things pinned down here both cost a whole extraction when they were wrong: the wire
 * shape the callback carries, which the consumer could only read in its multi-invoice form, and how
 * many pages go into a single vision request, which is what overflows the model's context window.
 */
class StartPiDataExtractionJobHandlerTest {

  private OcrJobRepository repository;
  private PdfPageExtractor pdfPageExtractor;
  private ImagePreprocessor imagePreprocessor;
  private OllamaVisionPort ollamaVisionPort;
  private StreamEventPublisher eventPublisher;
  private JobCallbackPort jobCallbackPort;
  private OcrProperties ocrProperties;
  private OllamaProperties ollamaProperties;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /** Runs inline so the test observes the finished job rather than racing the worker pool. */
  private final Executor directExecutor = Runnable::run;

  private final Map<JobId, OcrJob> saved = new HashMap<>();

  @BeforeEach
  void setUp() throws Exception {
    repository = mock(OcrJobRepository.class);
    pdfPageExtractor = mock(PdfPageExtractor.class);
    imagePreprocessor = mock(ImagePreprocessor.class);
    ollamaVisionPort = mock(OllamaVisionPort.class);
    eventPublisher = mock(StreamEventPublisher.class);
    jobCallbackPort = mock(JobCallbackPort.class);

    ocrProperties = new OcrProperties(150, 1024, 3, Boolean.FALSE);
    ollamaProperties = new OllamaProperties(
        "http://localhost:11434", "qwen2.5vl:3b", null, null, null, 8192, 60000,
        2048, 1.1, 320, 0, "ocr prompt", "pi prompt");

    doAnswer(invocation -> {
      OcrJob job = invocation.getArgument(0);
      saved.put(job.getJobId(), job);
      return null;
    }).when(repository).save(any(OcrJob.class));
    when(repository.findById(any(JobId.class)))
        .thenAnswer(invocation -> Optional.ofNullable(saved.get(invocation.getArgument(0))));

    BufferedImage page = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
    when(pdfPageExtractor.renderPage(any(), anyInt(), anyInt())).thenReturn(page);
    when(imagePreprocessor.preprocess(any(), anyInt(), anyInt()))
        .thenReturn(new ImagePreprocessor.PreprocessResult(
            page, new PageMetadata(150, true, 10, 10, 1L)));
  }

  private StartPiDataExtractionJobHandler newHandler() {
    return new StartPiDataExtractionJobHandler(
        repository, pdfPageExtractor, imagePreprocessor, ollamaVisionPort, eventPublisher,
        jobCallbackPort, ocrProperties, ollamaProperties, objectMapper, directExecutor);
  }

  private void modelReturns(String json) {
    doAnswer(invocation -> {
      Consumer<String> onChunk = invocation.getArgument(3);
      onChunk.accept(json);
      return null;
    }).when(ollamaVisionPort).streamVision(any(List.class), anyString(), anyBoolean(), any());
  }

  private JsonNode callbackExtraction() throws Exception {
    ArgumentCaptor<OcrJobResponse> captor = ArgumentCaptor.forClass(OcrJobResponse.class);
    verify(jobCallbackPort).postJobResult(eq("http://callback"), captor.capture());
    return objectMapper.readTree(captor.getValue().firstPageText());
  }

  private StartPiDataExtractionJobCommand command(int pageCount) throws Exception {
    when(pdfPageExtractor.countPages(any())).thenReturn(pageCount);
    return new StartPiDataExtractionJobCommand(
        "pi.pdf", "%PDF-1.4".getBytes(), "application/pdf", "http://callback");
  }

  /**
   * The prompt asks for the bare header/items pair when the document holds one invoice. Sending
   * that shape on the wire meant the consumer, which reads the {@code PROFORMA_INVOICES} envelope,
   * found nothing — so the extraction succeeded and the caller still got no data.
   */
  @Test
  void singleInvoiceResult_isWrappedInTheProformaInvoicesEnvelope() throws Exception {
    modelReturns("""
        {
          "DEFN_PROFORMA_INVOICE": { "pi_no": "PI-001", "currency": "USD" },
          "DEFN_PROFORMA_INVOICE_HSC": [
            { "description": "Cotton shirts", "total_amount": 1958.07 }
          ]
        }
        """);

    newHandler().handle(command(1));

    JsonNode extraction = callbackExtraction();
    assertThat(extraction.has("PROFORMA_INVOICES")).isTrue();
    assertThat(extraction.get("PROFORMA_INVOICES")).hasSize(1);

    JsonNode entry = extraction.get("PROFORMA_INVOICES").get(0);
    assertThat(entry.get("unique_id").asText()).isEqualTo("pi-1");
    assertThat(entry.get("DEFN_PROFORMA_INVOICE").get("pi_no").asText()).isEqualTo("PI-001");
    assertThat(entry.get("DEFN_PROFORMA_INVOICE_HSC")).hasSize(1);
  }

  /**
   * The HS code is usually nowhere near the goods table: the PI prints it once in a terms block and
   * leaves every row blank. A goods line that reaches the LC application without a code has to be
   * classified by hand, so the one code the document does state is copied onto every line.
   */
  @Test
  void hsCodeStatedOnlyInTheTerms_isCopiedOntoEveryGoodsLine() throws Exception {
    modelReturns("""
        {
          "DEFN_PROFORMA_INVOICE": {
            "pi_no": "PI-900",
            "remarks": "TERMS AND CONDITION:\\nHS CODE: 4819.10.00,\\nVAT NO: 001618357-002"
          },
          "DEFN_PROFORMA_INVOICE_HSC": [
            { "description": "Carton box", "quantity": 55, "unit_price": 81.92 },
            { "description": "Poly bag", "quantity": 20, "unit_price": 5.00 }
          ]
        }
        """);

    newHandler().handle(command(1));

    JsonNode items = callbackExtraction().get("PROFORMA_INVOICES").get(0)
        .get("DEFN_PROFORMA_INVOICE_HSC");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("hs_code").asText()).isEqualTo("4819.10.00");
    assertThat(items.get(1).get("hs_code").asText()).isEqualTo("4819.10.00");
  }

  /** A table that prints the code on its first row only, which is the other common layout. */
  @Test
  void hsCodeOnTheFirstRowOnly_fillsTheRowsBelowIt() throws Exception {
    modelReturns("""
        {
          "DEFN_PROFORMA_INVOICE": { "pi_no": "PI-901" },
          "DEFN_PROFORMA_INVOICE_HSC": [
            { "description": "Shirts", "hs_code": "6205.20.00" },
            { "description": "Trousers" }
          ]
        }
        """);

    newHandler().handle(command(1));

    JsonNode items = callbackExtraction().get("PROFORMA_INVOICES").get(0)
        .get("DEFN_PROFORMA_INVOICE_HSC");
    assertThat(items.get(1).get("hs_code").asText()).isEqualTo("6205.20.00");
  }

  /** A code found in the free-form content bag counts the same as one in a named field. */
  @Test
  void hsCodeBuriedInTheContentBag_stillReachesTheGoodsLines() throws Exception {
    modelReturns("""
        {
          "DEFN_PROFORMA_INVOICE": {
            "pi_no": "PI-903",
            "content": { "customs": { "HS Code": "6109.10.00" } }
          },
          "DEFN_PROFORMA_INVOICE_HSC": [ { "description": "T-shirts" } ]
        }
        """);

    newHandler().handle(command(1));

    JsonNode items = callbackExtraction().get("PROFORMA_INVOICES").get(0)
        .get("DEFN_PROFORMA_INVOICE_HSC");
    assertThat(items.get(0).get("hs_code").asText()).isEqualTo("6109.10.00");
  }

  /**
   * Copying one code across rows is only safe while the document states one code. Where the rows
   * are genuinely classified differently there is nothing to copy, and a blank row stays blank —
   * a wrong tariff code on a customs declaration costs more than a missing one.
   */
  @Test
  void rowsClassifiedDifferently_leaveABlankRowBlankRatherThanGuess() throws Exception {
    modelReturns("""
        {
          "DEFN_PROFORMA_INVOICE": { "pi_no": "PI-902" },
          "DEFN_PROFORMA_INVOICE_HSC": [
            { "description": "Shirts", "hs_code": "6205.20.00" },
            { "description": "Jackets", "hs_code": "6201.13.00" },
            { "description": "Buttons" }
          ]
        }
        """);

    newHandler().handle(command(1));

    JsonNode items = callbackExtraction().get("PROFORMA_INVOICES").get(0)
        .get("DEFN_PROFORMA_INVOICE_HSC");
    assertThat(items.get(0).get("hs_code").asText()).isEqualTo("6205.20.00");
    assertThat(items.get(1).get("hs_code").asText()).isEqualTo("6201.13.00");
    assertThat(items.get(2).get("hs_code").isNull()).isTrue();
  }

  /**
   * Amounts share the shape of an HS code, so only a code behind an explicit label is read as one.
   */
  @Test
  void amountsThatLookLikeCodes_areNotReadAsAnHsCode() throws Exception {
    modelReturns("""
        {
          "DEFN_PROFORMA_INVOICE": { "pi_no": "PI-904", "remarks": "IN WORD: 5945.44 ONLY" },
          "DEFN_PROFORMA_INVOICE_HSC": [
            { "description": "Labels", "quantity": 10, "unit_price": 594.54, "total_amount": 5945.44 }
          ]
        }
        """);

    newHandler().handle(command(1));

    JsonNode items = callbackExtraction().get("PROFORMA_INVOICES").get(0)
        .get("DEFN_PROFORMA_INVOICE_HSC");
    assertThat(items.get(0).get("hs_code").isNull()).isTrue();
  }

  /**
   * The exact failure seen in production: no SWIFT code is printed on the document, but a small
   * vision model fabricated one from the nearby phone number instead of returning null. Anything
   * that is not shaped like a real SWIFT/BIC is discarded rather than passed on as if it were read
   * from the page.
   */
  @Test
  void fabricatedSwiftValue_isDiscarded() throws Exception {
    modelReturns("""
        {
          "DEFN_PROFORMA_INVOICE": { "pi_no": "PI-905", "swift": "BDU1970D1970D1970D1970D1970D197" },
          "DEFN_PROFORMA_INVOICE_HSC": []
        }
        """);

    newHandler().handle(command(1));

    JsonNode header = callbackExtraction()
        .get("PROFORMA_INVOICES").get(0).get("DEFN_PROFORMA_INVOICE");
    assertThat(header.get("swift").isNull()).isTrue();
  }

  /** A genuine SWIFT/BIC is kept, normalized to upper case with no stray whitespace. */
  @Test
  void wellFormedSwiftValue_isKeptAndUppercased() throws Exception {
    modelReturns("""
        {
          "DEFN_PROFORMA_INVOICE": { "pi_no": "PI-906", "swift": "sebd bd dh" },
          "DEFN_PROFORMA_INVOICE_HSC": []
        }
        """);

    newHandler().handle(command(1));

    JsonNode header = callbackExtraction()
        .get("PROFORMA_INVOICES").get(0).get("DEFN_PROFORMA_INVOICE");
    assertThat(header.get("swift").asText()).isEqualTo("SEBDBDDH");
  }

  /** Not a real ISO code, so it is discarded rather than passed through as if it were read. */
  @Test
  void malformedCurrencyValue_isDiscarded() throws Exception {
    modelReturns("""
        {
          "DEFN_PROFORMA_INVOICE": { "pi_no": "PI-907", "currency": "US$" },
          "DEFN_PROFORMA_INVOICE_HSC": []
        }
        """);

    newHandler().handle(command(1));

    JsonNode header = callbackExtraction()
        .get("PROFORMA_INVOICES").get(0).get("DEFN_PROFORMA_INVOICE");
    assertThat(header.get("currency").isNull()).isTrue();
  }

  /** A malformed currency still falls through to text-based inference, same as a blank one would. */
  @Test
  void malformedCurrencyValue_stillFallsBackToTextInference() throws Exception {
    modelReturns("""
        {
          "DEFN_PROFORMA_INVOICE": {
            "pi_no": "PI-908", "currency": "Dollar",
            "remarks": "PAYMENT WILL BE MADE BY US DOLLAR"
          },
          "DEFN_PROFORMA_INVOICE_HSC": []
        }
        """);

    newHandler().handle(command(1));

    JsonNode header = callbackExtraction()
        .get("PROFORMA_INVOICES").get(0).get("DEFN_PROFORMA_INVOICE");
    assertThat(header.get("currency").asText()).isEqualTo("USD");
  }

  /** A day count wrapped in extra text (e.g. a fabricated unit) is reduced to the digits alone. */
  @Test
  void draftAtDaysWithExtraText_isReducedToTheDigitsAlone() throws Exception {
    modelReturns("""
        {
          "DEFN_PROFORMA_INVOICE": { "pi_no": "PI-909", "draft_at_days": "120 days sight" },
          "DEFN_PROFORMA_INVOICE_HSC": []
        }
        """);

    newHandler().handle(command(1));

    JsonNode header = callbackExtraction()
        .get("PROFORMA_INVOICES").get(0).get("DEFN_PROFORMA_INVOICE");
    assertThat(header.get("draft_at_days").asText()).isEqualTo("120");
  }

  /** No digits at all means the value was not really a day count, so it is dropped. */
  @Test
  void piValidityDaysWithNoDigits_isDiscarded() throws Exception {
    modelReturns("""
        {
          "DEFN_PROFORMA_INVOICE": { "pi_no": "PI-910", "pi_validity_days": "N/A" },
          "DEFN_PROFORMA_INVOICE_HSC": []
        }
        """);

    newHandler().handle(command(1));

    JsonNode header = callbackExtraction()
        .get("PROFORMA_INVOICES").get(0).get("DEFN_PROFORMA_INVOICE");
    assertThat(header.get("pi_validity_days").isNull()).isTrue();
  }

  @Test
  void multiInvoiceResult_keepsItsEnvelopeUntouched() throws Exception {
    modelReturns("""
        {
          "PROFORMA_INVOICES": [
            { "unique_id": "pi-1",
              "DEFN_PROFORMA_INVOICE": { "pi_no": "PI-100" },
              "DEFN_PROFORMA_INVOICE_HSC": [] },
            { "unique_id": "pi-2",
              "DEFN_PROFORMA_INVOICE": { "pi_no": "PI-200" },
              "DEFN_PROFORMA_INVOICE_HSC": [] }
          ]
        }
        """);

    newHandler().handle(command(1));

    JsonNode extraction = callbackExtraction();
    assertThat(extraction.get("PROFORMA_INVOICES")).hasSize(2);
    assertThat(extraction.get("PROFORMA_INVOICES").get(1).get("DEFN_PROFORMA_INVOICE")
        .get("pi_no").asText()).isEqualTo("PI-200");
  }

  /**
   * Every page goes into one chat request, and a rendered page costs one to two thousand vision
   * tokens, so an uncapped document overflows {@code num-ctx} and the model returns either an
   * error or truncated JSON.
   */
  @SuppressWarnings("unchecked")
  @Test
  void documentLongerThanMaxPages_sendsOnlyTheCappedNumberOfImages() throws Exception {
    modelReturns("{\"DEFN_PROFORMA_INVOICE\":{},\"DEFN_PROFORMA_INVOICE_HSC\":[]}");

    newHandler().handle(command(12));

    ArgumentCaptor<List<String>> images = ArgumentCaptor.forClass(List.class);
    verify(ollamaVisionPort).streamVision(images.capture(), anyString(), anyBoolean(), any());
    assertThat(images.getValue()).hasSize(3);
    verify(pdfPageExtractor, org.mockito.Mockito.times(3)).renderPage(any(), anyInt(), anyInt());
  }

  @Test
  void documentShorterThanMaxPages_sendsEveryPage() throws Exception {
    modelReturns("{\"DEFN_PROFORMA_INVOICE\":{},\"DEFN_PROFORMA_INVOICE_HSC\":[]}");

    newHandler().handle(command(2));

    verify(pdfPageExtractor, org.mockito.Mockito.times(2)).renderPage(any(), anyInt(), anyInt());
  }

  private void modelStreams(List<String> chunks) {
    doAnswer(invocation -> {
      Consumer<String> onChunk = invocation.getArgument(3);
      for (String chunk : chunks) {
        onChunk.accept(chunk);
      }
      return null;
    }).when(ollamaVisionPort).streamVision(any(List.class), anyString(), anyBoolean(), any());
  }

  private static final String LOOP_UNIT =
      "150, 19961-201 & Psc Certified Company, A Class of 1984 Export Oriented "
          + "Industries Packaging Factory, ";

  /**
   * The exact failure seen in production: correct header fields, then one address repeated until
   * the request timed out 30 minutes later. The guard must stop it, keep the fields that arrived,
   * complete the job, and say what happened.
   */
  @Test
  void loopingModel_isStoppedEarly_andTheGoodPrefixIsKept() throws Exception {
    String prefix = "{\"DEFN_PROFORMA_INVOICE\":{\"pi_no\":\"SPTL/881/2026/412\","
        + "\"applicant_name\":\"EVERBRIGHT SWEATER LTD.\",\"beneficiary_addr\":\"";
    java.util.List<String> chunks = new java.util.ArrayList<>();
    chunks.add(prefix);
    for (int i = 0; i < 200; i++) {
      chunks.add(LOOP_UNIT);
    }
    int[] delivered = {0};
    doAnswer(invocation -> {
      Consumer<String> onChunk = invocation.getArgument(3);
      for (String chunk : chunks) {
        onChunk.accept(chunk);
        delivered[0]++;
      }
      return null;
    }).when(ollamaVisionPort).streamVision(any(List.class), anyString(), anyBoolean(), any());

    JobId jobId = newHandler().handle(command(1));

    OcrJob job = saved.get(jobId);
    assertThat(job.getStatus().name()).isEqualTo("COMPLETED");
    // Stopped long before the stream ran out.
    assertThat(delivered[0]).isLessThan(50);
    assertThat(job.getWarnings()).anyMatch(w -> w.contains("repeated itself"));

    JsonNode header = callbackExtraction()
        .get("PROFORMA_INVOICES").get(0).get("DEFN_PROFORMA_INVOICE");
    assertThat(header.get("pi_no").asText()).isEqualTo("SPTL/881/2026/412");
    assertThat(header.get("applicant_name").asText()).isEqualTo("EVERBRIGHT SWEATER LTD.");
    assertThat(header.get("beneficiary_addr").asText().length()).isLessThan(LOOP_UNIT.length() * 2);
  }

  @Test
  void truncatedButNotLoopingOutput_isRepairedAndFlagged() throws Exception {
    modelStreams(List.of("{\"DEFN_PROFORMA_INVOICE\":{\"pi_no\":\"PI-5\",\"currency\":\"US"));

    JobId jobId = newHandler().handle(command(1));

    OcrJob job = saved.get(jobId);
    assertThat(job.getStatus().name()).isEqualTo("COMPLETED");
    assertThat(job.getWarnings()).anyMatch(w -> w.contains("repaired"));
    JsonNode header = callbackExtraction()
        .get("PROFORMA_INVOICES").get(0).get("DEFN_PROFORMA_INVOICE");
    assertThat(header.get("pi_no").asText()).isEqualTo("PI-5");
  }

  @Test
  void cleanOutput_carriesNoWarnings() throws Exception {
    modelReturns("{\"DEFN_PROFORMA_INVOICE\":{\"pi_no\":\"PI-1\"},\"DEFN_PROFORMA_INVOICE_HSC\":[]}");

    JobId jobId = newHandler().handle(command(1));

    assertThat(saved.get(jobId).getWarnings()).isEmpty();
  }

  /**
   * Callback delivery is the only way the caller learns the outcome, so a failed extraction has to
   * report as well as a successful one.
   */
  @Test
  void modelFailure_stillDeliversACallbackCarryingTheFailure() throws Exception {
    doAnswer(invocation -> {
      throw new IllegalStateException("Ollama produced no output for PT10M");
    }).when(ollamaVisionPort).streamVision(any(List.class), anyString(), anyBoolean(), any());

    newHandler().handle(command(1));

    ArgumentCaptor<OcrJobResponse> captor = ArgumentCaptor.forClass(OcrJobResponse.class);
    verify(jobCallbackPort).postJobResult(eq("http://callback"), captor.capture());
    assertThat(captor.getValue().status()).isEqualTo("FAILED");
    assertThat(captor.getValue().errorMessage()).contains("Ollama produced no output");
  }

  @Test
  void callbackDeliveryFailure_doesNotChangeTheJobOutcome() throws Exception {
    modelReturns("{\"DEFN_PROFORMA_INVOICE\":{\"pi_no\":\"PI-1\"},\"DEFN_PROFORMA_INVOICE_HSC\":[]}");
    doAnswer(invocation -> {
      throw new IllegalStateException("callback endpoint down");
    }).when(jobCallbackPort).postJobResult(anyString(), any());

    JobId jobId = newHandler().handle(command(1));

    assertThat(saved.get(jobId).getStatus().name()).isEqualTo("COMPLETED");
  }
}
