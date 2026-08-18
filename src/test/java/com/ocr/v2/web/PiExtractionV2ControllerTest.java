package com.ocr.v2.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocr.v2.DeterministicEmbeddingConfig;
import com.ocr.v2.config.AiProperties;
import com.ocr.v2.provider.ChatModelFactory;
import com.ocr.v2.provider.ChatModelRegistry;
import com.ocr.v2.provider.ProviderHandle;
import com.ocr.v2.provider.ProviderType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The v2 endpoints end to end, with a stub model standing in for the vendor call.
 *
 * <p>Everything else is real: a genuine PDF is rasterised and text-stripped, knowledge is retrieved
 * from pgvector, the response is normalised, verified against the PDF's own text layer, and
 * persisted. What is stubbed is only the one thing that would otherwise need a GPU and a network.
 */
@Testcontainers
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Import({DeterministicEmbeddingConfig.class, PiExtractionV2ControllerTest.StubChatConfig.class})
@EnabledIf("dockerAvailable")
class PiExtractionV2ControllerTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("ocr").withUsername("ocr").withPassword("ocr");

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("ai.rag.layout-pattern-threshold", () -> "0.0");
        registry.add("ai.rag.field-rule-threshold", () -> "0.0");
        registry.add("ai.rag.example-threshold", () -> "0.0");
        registry.add("ai.extraction.heartbeat-interval", () -> "1s");
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    ObjectMapper objectMapper;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("blocking extract returns the v1 shape plus verification and provenance")
    void blockingExtract() throws Exception {
        MvcResult started = mockMvc().perform(multipart("/api/v2/pi-extraction/extract")
                        .file(invoicePdf()))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc().perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("ollama"))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.extraction.DEFN_PROFORMA_INVOICE.pi_no").value("PI-2025/0042"))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);

        // The extraction block is exactly the v1 shape, so consumers migrate by changing the URL.
        assertThat(response.path("extraction").has("DEFN_PROFORMA_INVOICE")).isTrue();
        assertThat(response.path("extraction").has("DEFN_PROFORMA_INVOICE_HSC")).isTrue();
        // ...and everything new sits alongside it rather than inside it.
        assertThat(response.path("verification").path("status").asText()).isIn("PASS", "WARN");
        assertThat(response.path("knowledgeUsed")).isNotEmpty();
        assertThat(response.path("textLayerUsed").asBoolean()).isTrue();
        assertThat(response.path("requestId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("the normaliser runs on v2 output: the summary row is dropped and its total promoted")
    void appliesSharedNormalisation() throws Exception {
        MvcResult started = mockMvc().perform(multipart("/api/v2/pi-extraction/extract").file(invoicePdf()))
                .andExpect(request().asyncStarted()).andReturn();

        String body = mockMvc().perform(asyncDispatch(started))
                .andReturn().getResponse().getContentAsString();
        JsonNode extraction = objectMapper.readTree(body).path("extraction");

        // The stub returns three rows, the third being a TOTAL summary row.
        assertThat(extraction.path("DEFN_PROFORMA_INVOICE_HSC")).hasSize(2);
        assertThat(extraction.path("DEFN_PROFORMA_INVOICE").path("total_amount").decimalValue())
                .isEqualByComparingTo("4500.00");
    }

    @Test
    @DisplayName("useRag=false still injects the mandatory rules, never fewer")
    void ragDisabledKeepsMandatoryRules() throws Exception {
        MvcResult started = mockMvc().perform(multipart("/api/v2/pi-extraction/extract")
                        .file(invoicePdf())
                        .param("useRag", "false")
                        .param("useCache", "false"))
                .andExpect(request().asyncStarted()).andReturn();

        JsonNode response = objectMapper.readTree(mockMvc().perform(asyncDispatch(started))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(response.path("knowledgeUsed")).isNotEmpty();
        for (JsonNode used : response.path("knowledgeUsed")) {
            assertThat(used.path("mandatory").asBoolean()).isTrue();
        }
    }

    @Test
    @DisplayName("streaming emits stage events before deltas and ends with the same result")
    void streamingEmitsStagesThenResult() throws Exception {
        MvcResult started = mockMvc().perform(multipart("/api/v2/pi-extraction/extract/stream")
                        .file(invoicePdf())
                        .param("useCache", "false"))
                .andExpect(request().asyncStarted()).andReturn();

        // An SseEmitter writes to the response as it goes rather than setting a single async
        // result, so this waits for the terminal event instead of dispatching.
        String stream = awaitStreamCompletion(started, Duration.ofSeconds(60));

        int firstStage = stream.indexOf("event:stage");
        int firstDelta = stream.indexOf("event:delta");
        int result = stream.indexOf("event:result");

        assertThat(firstStage).isNotNegative();
        assertThat(result).isPositive();
        assertThat(firstStage).isLessThan(result);
        if (firstDelta >= 0) {
            assertThat(firstStage).isLessThan(firstDelta);
            assertThat(firstDelta).isLessThan(result);
        }
        assertThat(stream).contains("\"stage\":\"ingest\"");
        assertThat(stream).contains("\"stage\":\"verify\"");
    }

    @Test
    @DisplayName("an identical file under identical settings is served from cache")
    void cachesByteIdenticalInput() throws Exception {
        MockMultipartFile pdf = invoicePdf();

        MvcResult first = mockMvc().perform(multipart("/api/v2/pi-extraction/extract").file(pdf))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc().perform(asyncDispatch(first)).andExpect(status().isOk());

        MvcResult second = mockMvc().perform(multipart("/api/v2/pi-extraction/extract").file(pdf))
                .andExpect(request().asyncStarted()).andReturn();
        JsonNode response = objectMapper.readTree(mockMvc().perform(asyncDispatch(second))
                .andReturn().getResponse().getContentAsString());

        assertThat(response.path("cached").asBoolean()).isTrue();
        assertThat(response.path("extraction").path("DEFN_PROFORMA_INVOICE").path("pi_no").asText())
                .isEqualTo("PI-2025/0042");
    }

    @Test
    @DisplayName("an unknown provider is a clean 400, never a silent switch to another vendor")
    void unknownProviderIsRejected() throws Exception {
        MvcResult started = mockMvc().perform(multipart("/api/v2/pi-extraction/extract")
                        .file(invoicePdf())
                        .param("provider", "not-configured"))
                .andReturn();

        // Provider resolution happens inside the pipeline, so the error arrives via async dispatch.
        if (started.getRequest().isAsyncStarted()) {
            mockMvc().perform(asyncDispatch(started)).andExpect(status().isBadRequest());
        } else {
            assertThat(started.getResponse().getStatus()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("a non-document upload is rejected before any model is called")
    void rejectsUnsupportedFileType() throws Exception {
        mockMvc().perform(multipart("/api/v2/pi-extraction/extract")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("health is UP only when the knowledge base is both present and indexed")
    void healthReportsReadiness() throws Exception {
        String body = mockMvc().perform(get("/api/v2/pi-extraction/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("pi-extraction-v2"))
                .andExpect(jsonPath("$.defaultProvider").value("ollama"))
                .andExpect(jsonPath("$.knowledgeStoreReachable").value(true))
                .andExpect(jsonPath("$.status").value("UP"))
                .andReturn().getResponse().getContentAsString();

        JsonNode health = objectMapper.readTree(body);
        // Rows in the table without vectors behind them would degrade retrieval silently.
        assertThat(health.path("knowledgeDocuments").asLong()).isPositive();
        assertThat(health.path("knowledgeIndexed").asLong())
                .isGreaterThanOrEqualTo(health.path("knowledgeDocuments").asLong());
    }

    @Test
    @DisplayName("v1 endpoints are untouched by v2")
    void v1StillResponds() throws Exception {
        mockMvc().perform(get("/api/v1/pi-data-extraction/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("pi-data-extraction"));
    }

    // ------------------------------------------------------------------ helpers

    /** Polls the response buffer until the stream reaches a terminal event. */
    private String awaitStreamCompletion(MvcResult result, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        String content = "";
        while (System.currentTimeMillis() < deadline) {
            content = result.getResponse().getContentAsString();
            if (content.contains("event:result") || content.contains("event:error")) {
                return content;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Stream did not reach a terminal event within " + timeout
                + ". Received so far:\n" + content);
    }

    // ------------------------------------------------------------------ fixtures

    /** A real one-page PDF with a text layer, so grounding checks have something to check. */
    private MockMultipartFile invoicePdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.beginText();
                content.newLineAtOffset(40, 760);
                for (String line : STUB_DOCUMENT_LINES) {
                    content.showText(line);
                    content.newLineAtOffset(0, -16);
                }
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return new MockMultipartFile("file", "invoice.pdf", MediaType.APPLICATION_PDF_VALUE, out.toByteArray());
        }
    }

    private static final List<String> STUB_DOCUMENT_LINES = List.of(
            "ACME TEXTILES LTD",
            "PROFORMA INVOICE",
            "PI No: PI-2025/0042    Date: 15-Mar-2025",
            "To: NORTHERN IMPORTS LTD",
            "SL STYLE DESCRIPTION QTY UNIT UNIT PRICE (US$) AMOUNT",
            "1 ST-1001 Cotton Shirts 1000 PCS 2.50 2500.00",
            "2 ST-1002 Denim Trousers 500 PCS 4.00 2000.00",
            "TOTAL 4500.00",
            "PAYMENT: LC AT 120 DAYS SIGHT",
            "H.S CODE : 6205.20.00");

    /** What the stub model "reads" - including a summary row the normaliser must drop. */
    private static final String STUB_RESPONSE = """
            {"DEFN_PROFORMA_INVOICE":{
               "pi_no":"PI-2025/0042","pi_date":"2025-03-15",
               "applicant_name":"NORTHERN IMPORTS LTD","beneficiary_name":"ACME TEXTILES LTD",
               "currency":"USD","payment_terms":"LC AT 120 DAYS SIGHT","draft_at_days":null,
               "total_amount":null,"status":"Draft","remarks":null,"content":null},
             "DEFN_PROFORMA_INVOICE_HSC":[
               {"sl_no":1,"style_no":"ST-1001","description":"Cotton Shirts","quantity":1000,
                "quantity_unit":"PCS","unit_price":2.50,"total_amount":2500.00,"hs_code":null},
               {"sl_no":2,"style_no":"ST-1002","description":"Denim Trousers","quantity":500,
                "quantity_unit":"PCS","unit_price":4.00,"total_amount":2000.00,"hs_code":null},
               {"sl_no":3,"style_no":null,"description":"TOTAL","total_amount":4500.00}
             ]}
            """;

    /** Replaces the provider registry with one backed by a canned response. */
    @TestConfiguration
    static class StubChatConfig {

        @Bean
        @Primary
        ChatModelRegistry chatModelRegistry(AiProperties properties, ChatModelFactory factory) {
            ChatModel stub = new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return response(STUB_RESPONSE);
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    // Chunked, so the streaming endpoint has real deltas to forward.
                    return Flux.fromIterable(chunk(STUB_RESPONSE)).map(StubChatConfig::response);
                }
            };

            return new ChatModelRegistry(properties, factory) {
                private final ProviderHandle handle = new ProviderHandle(
                        "ollama", ProviderType.OLLAMA, "stub-model", 40, true,
                        Duration.ofMinutes(2), stub, (model, json) -> null);

                @Override
                public ProviderHandle resolve(String requestedProvider) {
                    if (requestedProvider == null || requestedProvider.isBlank()
                            || requestedProvider.equalsIgnoreCase("ollama")) {
                        return handle;
                    }
                    return super.resolve(requestedProvider);
                }
            };
        }

        static ChatResponse response(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }

        static List<String> chunk(String text) {
            return List.of(
                    text.substring(0, text.length() / 3),
                    text.substring(text.length() / 3, 2 * text.length() / 3),
                    text.substring(2 * text.length() / 3));
        }
    }
}
