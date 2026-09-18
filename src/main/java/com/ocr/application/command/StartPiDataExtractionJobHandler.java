package com.ocr.application.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ocr.application.dto.OcrJobResponse;
import com.ocr.application.port.JobCallbackPort;
import com.ocr.application.dto.StreamEvent;
import com.ocr.application.port.OllamaVisionPort;
import com.ocr.application.port.StreamEventPublisher;
import com.ocr.domain.model.JobId;
import com.ocr.domain.model.OcrJob;
import com.ocr.domain.model.OcrPage;
import com.ocr.domain.model.PageMetadata;
import com.ocr.domain.repository.OcrJobRepository;
import com.ocr.domain.service.ImagePreprocessor;
import com.ocr.domain.service.JsonSalvage;
import com.ocr.domain.service.RepetitionGuard;
import com.ocr.domain.service.PdfPageExtractor;
import com.ocr.interfaces.config.OcrProperties;
import com.ocr.interfaces.config.OllamaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StartPiDataExtractionJobHandler {

    private static final Logger log = LoggerFactory.getLogger(StartPiDataExtractionJobHandler.class);
    // Accepts partial codes the model reads from scans, e.g. 4819.1, 4819.10, 4819.10.00.
    private static final Pattern HS_CODE_PATTERN = Pattern.compile("\\b\\d{4}(?:\\.\\d{1,2}){1,2}\\b");
    // Matches a code only when it follows an explicit HS-code label, in any of the spellings a
    // scan produces: "HS CODE:", "H.S. CODE -", "HSCODE", "HS Code No. #".
    private static final Pattern LABELLED_HS_CODE_PATTERN = Pattern.compile(
            "(?i)\\bH\\.?\\s*S\\.?\\s*CODE[Ss]?\\s*(?:NO\\.?|#)?\\s*[:\\-]?\\s*"
                    + "(\\d{4}(?:\\s*\\.\\s*\\d{1,4}){0,2})");
    private static final Pattern DRAFT_DAYS_PATTERN = Pattern.compile("(?i)\\b(?:at\\s*)?(\\d{1,3})\\s*days?\\s+sight\\b");
    private static final Pattern USD_HINT_PATTERN = Pattern.compile("(?i)\\bU\\.?S\\.?\\s*(?:\\$|DOLLAR)|\\bUSD\\b|\\bUS\\$");
    private static final Pattern EUR_HINT_PATTERN = Pattern.compile("(?i)\\bEURO?\\b|€");
    private static final Pattern GBP_HINT_PATTERN = Pattern.compile("(?i)\\bGBP\\b|\\bPOUND\\s+STERLING\\b|£");

    private final OcrJobRepository repository;
    private final PdfPageExtractor pdfPageExtractor;
    private final ImagePreprocessor imagePreprocessor;
    private final OllamaVisionPort ollamaVisionPort;
    private final StreamEventPublisher eventPublisher;
    private final JobCallbackPort jobCallbackPort;
    private final OcrProperties ocrProperties;
    private final OllamaProperties ollamaProperties;
    private final ObjectMapper objectMapper;
    private final Executor ocrTaskExecutor;

    public StartPiDataExtractionJobHandler(
            OcrJobRepository repository,
            PdfPageExtractor pdfPageExtractor,
            ImagePreprocessor imagePreprocessor,
            OllamaVisionPort ollamaVisionPort,
            StreamEventPublisher eventPublisher,
            JobCallbackPort jobCallbackPort,
            OcrProperties ocrProperties,
            OllamaProperties ollamaProperties,
            ObjectMapper objectMapper,
            @Qualifier("ocrTaskExecutor") Executor ocrTaskExecutor
    ) {
        this.repository = repository;
        this.pdfPageExtractor = pdfPageExtractor;
        this.imagePreprocessor = imagePreprocessor;
        this.ollamaVisionPort = ollamaVisionPort;
        this.eventPublisher = eventPublisher;
        this.jobCallbackPort = jobCallbackPort;
        this.ocrProperties = ocrProperties;
        this.ollamaProperties = ollamaProperties;
        this.objectMapper = objectMapper;
        this.ocrTaskExecutor = ocrTaskExecutor;
    }

    public JobId handle(StartPiDataExtractionJobCommand command) throws IOException {
        JobId jobId = JobId.generate();
        OcrJob job = new OcrJob(jobId, command.fileName());
        boolean pdf = isPdf(command);
        int totalPages = pdf ? pdfPageExtractor.countPages(command.pdfBytes()) : 1;
        job.initializePages(1);
        repository.save(job);
        log.info(
                "Queued PI extraction job: jobId={}, fileName={}, inputType={}, sourcePages={}, callbackUrl={}",
                jobId,
                command.fileName(),
                pdf ? "PDF" : "IMAGE",
                totalPages,
                command.callbackUrl()
        );

        ocrTaskExecutor.execute(() -> processJob(command, jobId, totalPages));

        return jobId;
    }

    private void processJob(StartPiDataExtractionJobCommand command, JobId jobId, int sourcePageCount) {
        OcrJob job = repository.findById(jobId).orElseThrow();
        job.startProcessing();
        repository.save(job);
        log.info("Started PI extraction job processing: jobId={}, sourcePages={}", jobId, sourcePageCount);

        eventPublisher.publish(StreamEvent.jobStarted(
                jobId.toString(),
                job.getFileName(),
                sourcePageCount
        ));

        try {
            processDocument(job, command, sourcePageCount);
            job.complete();
            repository.save(job);
            OcrJobResponse jobResponse = OcrJobResponse.from(job);
            log.info("PI extraction job completed successfully: jobId={}, status={}", jobId, jobResponse.status());
            eventPublisher.publish(StreamEvent.jobCompleted(
                    jobId.toString(),
                    jobResponse
            ));
            sendCallback(command.callbackUrl(), jobResponse);
        } catch (Exception e) {
            job.fail(e.getMessage());
            repository.save(job);
            log.error("PI extraction job failed: jobId={}, error={}", jobId, e.getMessage(), e);
            eventPublisher.publish(StreamEvent.error(jobId.toString(), e.getMessage(), null));
            sendCallback(command.callbackUrl(), OcrJobResponse.from(job));
        }
    }

    private void sendCallback(String callbackUrl, OcrJobResponse jobResponse) {
        try {
            log.info(
                    "Sending PI extraction callback: jobId={}, status={}, callbackUrl={}",
                    jobResponse.jobId(),
                    jobResponse.status(),
                    callbackUrl
            );
            jobCallbackPort.postJobResult(callbackUrl, jobResponse);
            log.info("PI extraction callback finished: jobId={}, callbackUrl={}", jobResponse.jobId(), callbackUrl);
        } catch (Exception callbackException) {
            // Callback delivery must not change the OCR job result after processing finishes.
            log.error("Failed to deliver callback for OCR job {} to {}", jobResponse.jobId(), callbackUrl, callbackException);
        }
    }

    private void processDocument(OcrJob job, StartPiDataExtractionJobCommand command, int sourcePageCount) throws IOException {
        JobId jobId = job.getJobId();
        OcrPage page = job.getPages().get(0);
        int resultNum = page.getPageNumber().value();
        boolean pdf = isPdf(command);

        page.startProcessing();
        repository.save(job);
        eventPublisher.publish(StreamEvent.pageStarted(jobId.toString(), resultNum, 1));
        log.info(
                "Preparing PI extraction document payload: jobId={}, page={}, inputType={}, sourcePages={}",
                jobId,
                resultNum,
                pdf ? "PDF" : "IMAGE",
                sourcePageCount
        );

        long startMs = System.currentTimeMillis();
        try {
            List<String> base64Pages = new ArrayList<>();
            int maxWidth = 0;
            int maxHeight = 0;

            int pagesToAnalyze = Math.min(sourcePageCount, ocrProperties.maxPages());
            if (pagesToAnalyze < sourcePageCount) {
                log.warn(
                        "Truncating document for PI extraction: jobId={}, sourcePages={}, analyzedPages={}, "
                                + "reason=ocr.max-pages. Every page is sent in one vision request and would "
                                + "otherwise overflow ollama.num-ctx.",
                        jobId,
                        sourcePageCount,
                        pagesToAnalyze
                );
            }

            if (pdf) {
                for (int pageIndex = 0; pageIndex < pagesToAnalyze; pageIndex++) {
                    BufferedImage rendered = pdfPageExtractor.renderPage(
                            command.pdfBytes(),
                            pageIndex,
                            ocrProperties.renderDpi()
                    );
                    ImagePreprocessor.PreprocessResult preprocessed = imagePreprocessor.preprocess(
                            rendered,
                            ocrProperties.renderDpi(),
                            ocrProperties.maxImageDimension()
                    );
                    base64Pages.add(toBase64Png(preprocessed.image()));
                    maxWidth = Math.max(maxWidth, preprocessed.metadata().width());
                    maxHeight = Math.max(maxHeight, preprocessed.metadata().height());
                }
                log.info("Prepared PDF pages for PI extraction: jobId={}, preparedImages={}", jobId, base64Pages.size());
            } else {
                ImagePreprocessor.PreprocessResult preprocessed = preprocessUploadedImage(command.pdfBytes());
                base64Pages.add(toBase64Png(preprocessed.image()));
                maxWidth = preprocessed.metadata().width();
                maxHeight = preprocessed.metadata().height();
                log.info("Prepared image upload for PI extraction: jobId={}, width={}, height={}", jobId, maxWidth, maxHeight);
            }

            log.info("Invoking vision model for PI extraction: jobId={}, imageCount={}", jobId, base64Pages.size());
            // Job-based flow: accumulate the streamed tokens in memory; clients poll the job
            // status instead of consuming per-token events. The guard rides along so a model that
            // starts repeating itself is stopped at the first repeat rather than at the timeout.
            RepetitionGuard guard = new RepetitionGuard();
            try {
                ollamaVisionPort.streamVision(
                        base64Pages,
                        promptForDocument(base64Pages.size()),
                        true,
                        chunk -> {
                            // Keep updating the page as tokens arrive: an in-flight job stays
                            // inspectable, which is how a stall gets diagnosed at all.
                            page.appendText(chunk);
                            if (guard.append(chunk)) {
                                throw new DegenerateOutputException();
                            }
                        }
                );
            } catch (DegenerateOutputException e) {
                log.warn(
                        "Vision model began repeating itself, stopping early: jobId={}, period={}, "
                                + "keptChars={}, discardedChars={}",
                        jobId,
                        guard.period(),
                        guard.loopStartIndex(),
                        guard.text().length() - guard.loopStartIndex()
                );
                job.addWarning("The model repeated itself and generation was stopped early; "
                        + "fields after that point are missing.");
            }
            log.info("Vision model stream finished for PI extraction: jobId={}, outputChars={}",
                    jobId, guard.text().length());

            String extracted = guard.textWithoutLoop();
            String closed = JsonSalvage.close(extracted);
            if (!closed.equals(extracted)) {
                log.warn("Extraction JSON was incomplete and had to be closed: jobId={}", jobId);
                job.addWarning("The extraction was cut short and its JSON had to be repaired; "
                        + "some fields may be missing.");
            }
            page.replaceText(normalizeExtraction(closed));

            long durationMs = System.currentTimeMillis() - startMs;
            PageMetadata metadata = new PageMetadata(
                    ocrProperties.renderDpi(),
                    true,
                    maxWidth,
                    maxHeight,
                    durationMs
            );
            page.complete(metadata);
            repository.save(job);
            Map<String, Object> eventMetadata = metadata.toMap();
            eventMetadata.put("sourcePageCount", sourcePageCount);
            eventMetadata.put("analyzedPageCount", base64Pages.size());
            eventMetadata.put("analyzedAs", "single-document");
            log.info(
                    "PI extraction page completed: jobId={}, page={}, durationMs={}, width={}, height={}",
                    jobId,
                    resultNum,
                    durationMs,
                    maxWidth,
                    maxHeight
            );
            eventPublisher.publish(StreamEvent.pageCompleted(
                    jobId.toString(),
                    resultNum,
                    page.getExtractedText(),
                    eventMetadata
            ));
        } catch (Exception e) {
            page.fail(e.getMessage());
            repository.save(job);
            eventPublisher.publish(StreamEvent.error(jobId.toString(), e.getMessage(), resultNum));
            throw e;
        }
    }

    /** Unwinds the reactive stream when the guard trips. Never leaves this class. */
    private static final class DegenerateOutputException extends RuntimeException {
        DegenerateOutputException() {
            super(null, null, false, false);
        }
    }

    private String promptForDocument(int sourcePageCount) {
        return ollamaProperties.piDataExtractionPrompt()
                + "\nThe attached images are the full document in order. Page count: " + sourcePageCount + ".";
    }

    private boolean isPdf(StartPiDataExtractionJobCommand command) {
        String contentType = command.contentType();
        if (contentType != null && contentType.equalsIgnoreCase("application/pdf")) {
            return true;
        }
        String fileName = command.fileName();
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    private ImagePreprocessor.PreprocessResult preprocessUploadedImage(byte[] fileBytes) throws IOException {
        BufferedImage sourceImage = ImageIO.read(new ByteArrayInputStream(fileBytes));
        if (sourceImage == null) {
            throw new IllegalArgumentException("Invalid image file");
        }
        return imagePreprocessor.preprocess(
                sourceImage,
                ocrProperties.renderDpi(),
                ocrProperties.maxImageDimension()
        );
    }

    private String normalizeExtraction(String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            if (!(root instanceof ObjectNode rootObject)) {
                return text;
            }

            if (rootObject.has("PROFORMA_INVOICES")) {
                normalizeMultipleInvoices(rootObject.withArray("PROFORMA_INVOICES"));
            } else {
                normalizeInvoice(rootObject);
                rootObject = wrapSingleInvoice(rootObject);
            }

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootObject);
        } catch (Exception ignored) {
            return text;
        }
    }

    /**
     * Lifts a single-invoice document into the same {@code PROFORMA_INVOICES} envelope the
     * multi-invoice prompt produces. The model is told to return the bare
     * {@code DEFN_PROFORMA_INVOICE}/{@code DEFN_PROFORMA_INVOICE_HSC} pair for a one-PI document,
     * which left consumers with two different top-level shapes to handle; emitting one shape here
     * means the callback contract never varies with the page content.
     */
    private ObjectNode wrapSingleInvoice(ObjectNode invoice) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("unique_id", "pi-1");
        entry.set("DEFN_PROFORMA_INVOICE", objectNode(invoice, "DEFN_PROFORMA_INVOICE"));
        entry.set("DEFN_PROFORMA_INVOICE_HSC", arrayNode(invoice, "DEFN_PROFORMA_INVOICE_HSC"));

        ArrayNode invoices = objectMapper.createArrayNode();
        invoices.add(entry);

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("PROFORMA_INVOICES", invoices);
        return wrapper;
    }

    private void normalizeMultipleInvoices(ArrayNode invoices) {
        for (JsonNode invoiceNode : invoices) {
            if (invoiceNode instanceof ObjectNode invoice) {
                normalizeInvoice(invoice);
            }
        }
    }

    private void normalizeInvoice(ObjectNode invoice) {
        ObjectNode header = objectNode(invoice, "DEFN_PROFORMA_INVOICE");
        ArrayNode items = arrayNode(invoice, "DEFN_PROFORMA_INVOICE_HSC");
        String commonHsCode = resolveCommonHsCode(invoice, header, items);
        JsonNode removedTotal = removeSummaryRowsAndNormalizeItems(items, commonHsCode);

        if (!header.has("total_amount") || header.get("total_amount").isNull()) {
            JsonNode totalAmount = removedTotal != null ? removedTotal : sumItemAmounts(items);
            if (totalAmount != null) {
                header.set("total_amount", totalAmount);
            }
        } else {
            normalizeAmountField(header, "total_amount");
        }

        normalizeDraftAtDays(header);
        normalizePartialShipment(header);
        normalizeCurrency(header, invoice);
    }

    private void normalizeCurrency(ObjectNode header, ObjectNode invoice) {
        JsonNode currency = header.get("currency");
        if (currency != null && !currency.isNull() && !currency.asText().isBlank()) {
            return;
        }
        // Infer currency from any text in the invoice (payment terms, remarks, content, ...).
        String haystack = invoice.toString();
        String inferred = null;
        if (USD_HINT_PATTERN.matcher(haystack).find()) {
            inferred = "USD";
        } else if (EUR_HINT_PATTERN.matcher(haystack).find()) {
            inferred = "EUR";
        } else if (GBP_HINT_PATTERN.matcher(haystack).find()) {
            inferred = "GBP";
        }
        if (inferred != null) {
            header.put("currency", inferred);
            // Traceability: record that the currency was inferred from document text,
            // not read from a dedicated currency field.
            JsonNode content = header.get("content");
            if (content instanceof ObjectNode contentObject) {
                contentObject.put("CURRENCY_SOURCE", "inferred-from-text");
            }
        }
    }

    private ObjectNode objectNode(ObjectNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        ObjectNode objectNode = objectMapper.createObjectNode();
        parent.set(fieldName, objectNode);
        return objectNode;
    }

    private ArrayNode arrayNode(ObjectNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        if (node instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        ArrayNode arrayNode = objectMapper.createArrayNode();
        parent.set(fieldName, arrayNode);
        return arrayNode;
    }

    private JsonNode removeSummaryRowsAndNormalizeItems(ArrayNode items, String commonHsCode) {
        ArrayNode kept = objectMapper.createArrayNode();
        JsonNode removedTotal = null;
        int slNo = 1;

        for (JsonNode itemNode : items) {
            if (!(itemNode instanceof ObjectNode item)) {
                continue;
            }

            normalizeAmountField(item, "unit_price");
            normalizeAmountField(item, "total_amount");

            if (isSummaryRow(item)) {
                if (item.has("total_amount") && item.get("total_amount").isNumber()) {
                    removedTotal = item.get("total_amount");
                }
                continue;
            }

            normalizeHsCode(item, commonHsCode);
            item.put("sl_no", slNo++);
            kept.add(item);
        }

        items.removeAll();
        items.addAll(kept);
        return removedTotal;
    }

    private boolean isSummaryRow(ObjectNode item) {
        String styleNo = text(item, "style_no");
        String description = text(item, "description");
        String hsCode = text(item, "hs_code");
        String label = (styleNo + " " + description).toLowerCase();
        boolean hasNoProductIdentity = styleNo.isBlank() && description.isBlank() && hsCode.isBlank();
        return label.contains("total") || (hasNoProductIdentity && item.has("total_amount"));
    }

    /**
     * Gives every kept row an HS code: its own when the table printed one, otherwise the invoice's
     * single common code. The key is always written, so a consumer can tell "this document states
     * no HS code" from "the model dropped the field".
     */
    private void normalizeHsCode(ObjectNode item, String commonHsCode) {
        JsonNode own = item.get("hs_code");
        String text = own == null || own.isNull() ? "" : own.asText("").trim();
        if (!text.isBlank()) {
            item.put("hs_code", text);
        } else if (commonHsCode != null) {
            item.put("hs_code", commonHsCode);
        } else {
            item.putNull("hs_code");
        }
    }

    /**
     * Finds the one HS code that applies to every goods line, wherever the document printed it.
     *
     * <p>A proforma invoice usually states its HS code once and nowhere near the goods table — in a
     * terms block, a footer note, a header field, or only on the first row — and leaves the rest
     * blank. So codes are collected from the whole invoice rather than from the table alone, and
     * when all of them agree on one value, that value fills every row missing a code. This is
     * deliberately not tied to any one layout: what decides is the agreement between the codes
     * found, not where they were found.
     *
     * <p>Two or more distinct codes mean the document really does classify its rows differently.
     * Nothing is filled in then, and a blank row stays blank — a wrong HS code on a customs
     * declaration costs more than a missing one.
     */
    private String resolveCommonHsCode(ObjectNode invoice, ObjectNode header, ArrayNode items) {
        Set<String> codes = new LinkedHashSet<>();
        // Dedicated fields first: exact values, with no way for an amount to be read as a code.
        collectHsCodeFields(items, codes);
        collectHsCodeFields(header, codes);
        if (codes.isEmpty()) {
            // No field declared one, so fall back to labelled text anywhere in the invoice:
            // remarks, payment terms, the content bag, a raw table dump.
            collectLabelledHsCodes(invoice, codes);
        }
        return codes.size() == 1 ? codes.iterator().next() : null;
    }

    /** Walks a subtree and collects the value of every HS-code-named field in it, at any depth. */
    private void collectHsCodeFields(JsonNode node, Set<String> codes) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                collectHsCodeFields(element, codes);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (isHsCodeFieldName(field.getKey())) {
                collectHsCodeValues(field.getValue(), codes);
            } else {
                collectHsCodeFields(field.getValue(), codes);
            }
        }
    }

    /** Reads a code out of whatever an HS-code field holds: text, a number, or a list of either. */
    private void collectHsCodeValues(JsonNode value, Set<String> codes) {
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isArray()) {
            for (JsonNode element : value) {
                collectHsCodeValues(element, codes);
            }
            return;
        }
        if (value.isObject()) {
            collectHsCodeFields(value, codes);
            return;
        }
        String text = value.asText("").trim();
        if (text.isBlank()) {
            return;
        }
        Matcher matcher = HS_CODE_PATTERN.matcher(text);
        codes.add(matcher.find() ? matcher.group() : text);
    }

    /**
     * Collects every code that follows an explicit "HS CODE" label in any text in the subtree.
     *
     * <p>Each string value is matched on its own rather than on the serialized JSON, so a label and
     * a number that only became adjacent through serialization cannot be joined into a false match.
     */
    private void collectLabelledHsCodes(JsonNode node, Set<String> codes) {
        if (node == null) {
            return;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                collectLabelledHsCodes(child, codes);
            }
            return;
        }
        if (!node.isTextual()) {
            return;
        }
        Matcher matcher = LABELLED_HS_CODE_PATTERN.matcher(node.asText());
        while (matcher.find()) {
            codes.add(matcher.group(1).replaceAll("\\s+", ""));
        }
    }

    /** True for any spelling of an HS-code field name: hs_code, HS_CODE, hsCode, "HS Code", hscodes. */
    private boolean isHsCodeFieldName(String fieldName) {
        String normalized = fieldName.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
        return "hscode".equals(normalized) || "hscodes".equals(normalized);
    }

    private void normalizeAmountField(ObjectNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || value.isNumber()) {
            return;
        }
        String raw = value.asText();
        if (raw == null || raw.isBlank()) {
            node.putNull(fieldName);
            return;
        }
        String cleaned = raw.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank() || ".".equals(cleaned) || "-".equals(cleaned)) {
            return;
        }
        try {
            node.set(fieldName, DecimalNode.valueOf(new java.math.BigDecimal(cleaned)));
        } catch (NumberFormatException ignored) {
            // Keep the original value if it is not a clean amount.
        }
    }

    private JsonNode sumItemAmounts(ArrayNode items) {
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        boolean found = false;
        for (JsonNode item : items) {
            JsonNode amount = item.get("total_amount");
            if (amount != null && amount.isNumber()) {
                total = total.add(amount.decimalValue());
                found = true;
            }
        }
        return found ? DecimalNode.valueOf(total) : null;
    }

    private void normalizeDraftAtDays(ObjectNode header) {
        JsonNode draftAtDays = header.get("draft_at_days");
        if (draftAtDays != null && !draftAtDays.isNull() && !draftAtDays.asText().isBlank()) {
            return;
        }
        String paymentTerms = text(header, "payment_terms");
        Matcher matcher = DRAFT_DAYS_PATTERN.matcher(paymentTerms);
        if (matcher.find()) {
            header.put("draft_at_days", matcher.group(1));
        }
    }

    private void normalizePartialShipment(ObjectNode header) {
        JsonNode partialShipment = header.get("partial_shipment");
        if (partialShipment != null && !partialShipment.isNull() && !partialShipment.asText().isBlank()) {
            return;
        }
        String shipmentValidity = text(header, "shipment_validity").toLowerCase();
        if (shipmentValidity.contains("partial") && (shipmentValidity.contains("allow")
                || shipmentValidity.contains("permitted"))) {
            header.put("partial_shipment", "Allowed");
        }
    }

    private String text(ObjectNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String toBase64Png(BufferedImage image) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }
}
