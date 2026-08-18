package com.ocr.v2.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ocr.shared.pi.PiExtractionNormalizer;
import com.ocr.v2.config.AiProperties;
import com.ocr.v2.knowledge.KnowledgeService;
import com.ocr.v2.pdf.PdfDocumentReader;
import com.ocr.v2.pdf.PreparedDocument;
import com.ocr.v2.persistence.ExtractionCacheEntity;
import com.ocr.v2.persistence.ExtractionCacheRepository;
import com.ocr.v2.persistence.ExtractionRunEntity;
import com.ocr.v2.persistence.ExtractionRunRepository;
import com.ocr.v2.provider.ChatModelRegistry;
import com.ocr.v2.provider.ProviderHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import com.ocr.v2.config.V2Component;
import org.springframework.util.MimeTypeUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The v2 extraction pipeline: upload in, verified structured data out, synchronously.
 *
 * <pre>
 *   0 ingest    sha256, cache probe, render pages, read the text layer   (no LLM)
 *   1 signature what kind of document is this                            (no LLM for digital PDFs)
 *   2 retrieve  mandatory rules + knowledge matching that signature      (no LLM)
 *   3 extract   one streamed vision call                                 (the only LLM call)
 *   4 normalise the shared v1 deterministic rules
 *   5 verify    schema + arithmetic + grounding against the document     (no LLM)
 *   6 persist   audit row and cache entry
 * </pre>
 *
 * <p>Note where the model sits: one call, near the end, with everything already prepared. Stages
 * 0-2 and 4-6 are deterministic, which is what makes the output reviewable.
 */
@V2Component
public class ExtractionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ExtractionPipeline.class);

    private final PdfDocumentReader documentReader;
    private final DocumentSignatureBuilder signatureBuilder;
    private final KnowledgeRetriever knowledgeRetriever;
    private final KnowledgeService knowledgeService;
    private final PromptAssembler promptAssembler;
    private final JsonResponseExtractor jsonExtractor;
    private final PiExtractionNormalizer normalizer;
    private final ExtractionVerifier verifier;
    private final ChatModelRegistry providers;
    private final ExtractionRunRepository runRepository;
    private final ExtractionCacheRepository cacheRepository;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    public ExtractionPipeline(PdfDocumentReader documentReader,
                              DocumentSignatureBuilder signatureBuilder,
                              KnowledgeRetriever knowledgeRetriever,
                              KnowledgeService knowledgeService,
                              PromptAssembler promptAssembler,
                              JsonResponseExtractor jsonExtractor,
                              PiExtractionNormalizer normalizer,
                              ExtractionVerifier verifier,
                              ChatModelRegistry providers,
                              ExtractionRunRepository runRepository,
                              ExtractionCacheRepository cacheRepository,
                              AiProperties properties,
                              ObjectMapper objectMapper) {
        this.documentReader = documentReader;
        this.signatureBuilder = signatureBuilder;
        this.knowledgeRetriever = knowledgeRetriever;
        this.knowledgeService = knowledgeService;
        this.promptAssembler = promptAssembler;
        this.jsonExtractor = jsonExtractor;
        this.normalizer = normalizer;
        this.verifier = verifier;
        this.providers = providers;
        this.runRepository = runRepository;
        this.cacheRepository = cacheRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ExtractionResult extract(ExtractionRequest request, ExtractionListener listener) throws Exception {
        long startedAt = System.currentTimeMillis();
        UUID requestId = UUID.randomUUID();
        ProviderHandle provider = providers.resolve(request.provider());
        String model = provider.modelOr(request.model());

        // --- 0. ingest -------------------------------------------------------------------
        listener.onStage("ingest", "Reading document");
        String fileSha = sha256(request.bytes());
        long knowledgeVersion = knowledgeService.knowledgeVersion();
        String cacheKey = cacheKey(fileSha, provider.name(), model, request, knowledgeVersion);

        if (request.useCache() && properties.extraction().cacheEnabled()) {
            Optional<ExtractionResult> hit = fromCache(cacheKey, requestId, request, provider, model, startedAt);
            if (hit.isPresent()) {
                listener.onStage("cached", "Identical document already extracted under the same configuration");
                return hit.get();
            }
        }

        PreparedDocument document = documentReader.read(request.bytes(), request.fileName(), request.contentType());
        guardImageCount(document, provider);

        // --- 1. signature ----------------------------------------------------------------
        listener.onStage("signature", "Identifying document layout");
        DocumentSignature signature = buildSignature(document, provider, request);

        // --- 2. retrieve -----------------------------------------------------------------
        listener.onStage("retrieve", "Selecting extraction rules");
        RetrievedKnowledge knowledge = knowledgeRetriever.retrieve(signature, request.useRag());
        log.info("Extraction {}: provider={}, model={}, pages={}, signature={}, knowledgeChunks={} ({} mandatory)",
                requestId, provider.name(), model, document.pageCount(), signature.source(),
                knowledge.chunks().size(), knowledge.chunks().stream().filter(c -> c.mandatory()).count());

        // --- 3. extract ------------------------------------------------------------------
        listener.onStage("extract", "Reading the document with " + provider.name() + "/" + model);
        boolean textLayerUsed = request.includeTextLayer()
                && properties.extraction().includeTextLayer()
                && document.textLayerUsable();
        List<Message> messages = promptAssembler.assemble(document, knowledge, request.includeTextLayer());

        ModelCall call;
        try {
            call = callModel(provider, model, messages, listener);
        } catch (Exception e) {
            persistFailure(requestId, request, fileSha, document, provider, model, knowledgeVersion,
                    knowledge, textLayerUsed, System.currentTimeMillis() - startedAt, e);
            throw e;
        }

        // --- 4. normalise ----------------------------------------------------------------
        listener.onStage("normalise", "Applying deterministic field rules");
        String json = jsonExtractor.extract(call.text());
        JsonNode extraction = parseOrFail(json, requestId, request, fileSha, document, provider, model,
                knowledgeVersion, knowledge, textLayerUsed, startedAt);
        if (extraction instanceof ObjectNode objectNode) {
            normalizer.normalizeTree(objectNode);
        }

        // --- 5. verify -------------------------------------------------------------------
        listener.onStage("verify", "Checking the extraction against the document");
        VerificationReport verification = verifier.verify(
                extraction, document.textLayer(), document.textLayerUsable());

        long durationMs = System.currentTimeMillis() - startedAt;
        ExtractionResult result = new ExtractionResult(
                requestId,
                request.fileName(),
                provider.name(),
                model,
                document.pageCount(),
                durationMs,
                false,
                textLayerUsed,
                signature.source().name(),
                extraction,
                verification,
                toKnowledgeUsed(knowledge),
                call.usage());

        // --- 6. persist ------------------------------------------------------------------
        persistSuccess(result, request, fileSha, document, knowledgeVersion, knowledge, cacheKey);
        log.info("Extraction {} finished: status={}, durationMs={}, grounded={}/{}",
                requestId, verification.status(), durationMs,
                verification.fieldsGrounded(), verification.fieldsChecked());
        return result;
    }

    // ------------------------------------------------------------------ stages

    private void guardImageCount(PreparedDocument document, ProviderHandle provider) {
        if (document.imageCount() > provider.maxImages()) {
            throw new IllegalArgumentException(String.format(
                    "Document has %d pages but provider '%s' accepts at most %d images per request. "
                            + "Split the document or use a provider with a higher limit.",
                    document.imageCount(), provider.name(), provider.maxImages()));
        }
    }

    /**
     * Digital PDFs get their signature from the text layer for free. Only scans pay for a small
     * vision call, and that call is told to report structure and no values at all.
     */
    private DocumentSignature buildSignature(PreparedDocument document, ProviderHandle provider,
                                             ExtractionRequest request) {
        if (!request.useRag() || !properties.rag().enabled()) {
            return DocumentSignature.none();
        }
        if (document.textLayerUsable()) {
            return signatureBuilder.fromTextLayer(document.textLayer());
        }
        if (document.base64Images().isEmpty()) {
            return DocumentSignature.none();
        }
        try {
            Media firstPage = Media.builder()
                    .mimeType(MimeTypeUtils.IMAGE_PNG)
                    .data(Base64.getDecoder().decode(document.base64Images().get(0)))
                    .build();
            String summary = ChatClient.create(provider.chatModel())
                    .prompt(new Prompt(
                            List.of(UserMessage.builder()
                                    .text(PromptAssembler.SIGNATURE_PROMPT)
                                    .media(firstPage)
                                    .build()),
                            provider.options(null, false)))
                    .call()
                    .content();
            return signatureBuilder.fromVisionSummary(summary);
        } catch (Exception e) {
            // Losing the signature costs relevance, not correctness: mandatory rules still apply.
            log.warn("Vision signature pass failed, continuing with mandatory knowledge only: {}", e.getMessage());
            return DocumentSignature.none();
        }
    }

    /**
     * The single model call, streamed. Streaming is not cosmetic here: it keeps bytes flowing to
     * the client for the whole of a multi-minute extraction, which is what stops proxies and load
     * balancers from killing the connection.
     */
    private ModelCall callModel(ProviderHandle provider, String model, List<Message> messages,
                                ExtractionListener listener) {
        StringBuilder buffer = new StringBuilder();
        AtomicReference<Usage> usage = new AtomicReference<>();

        ChatClient.create(provider.chatModel())
                .prompt(new Prompt(messages, provider.options(model, true)))
                .stream()
                .chatResponse()
                .doOnNext(response -> {
                    String delta = textOf(response);
                    if (!delta.isEmpty()) {
                        buffer.append(delta);
                        listener.onDelta(delta);
                    }
                    if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                        usage.set(response.getMetadata().getUsage());
                    }
                })
                // Server-side hard cap only. There is no client-facing timeout: the caller's
                // connection is kept alive by the stream itself for as long as this takes.
                .blockLast(provider.timeout());

        Usage finalUsage = usage.get();
        return new ModelCall(buffer.toString(), finalUsage == null
                ? ExtractionResult.Usage.EMPTY
                : new ExtractionResult.Usage(finalUsage.getPromptTokens(), finalUsage.getCompletionTokens()));
    }

    private String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    // ------------------------------------------------------------------ cache

    private Optional<ExtractionResult> fromCache(String cacheKey, UUID requestId, ExtractionRequest request,
                                                 ProviderHandle provider, String model, long startedAt) {
        try {
            return cacheRepository.findById(cacheKey).map(entry -> {
                try {
                    entry.recordHit();
                    cacheRepository.save(entry);
                    return new ExtractionResult(
                            requestId,
                            request.fileName(),
                            provider.name(),
                            model,
                            entry.getPageCount(),
                            System.currentTimeMillis() - startedAt,
                            true,
                            false,
                            DocumentSignature.Source.NONE.name(),
                            objectMapper.readTree(entry.getResult()),
                            objectMapper.readValue(entry.getVerification(), VerificationReport.class),
                            List.of(),
                            ExtractionResult.Usage.EMPTY);
                } catch (Exception e) {
                    log.warn("Cached entry {} could not be read back, re-extracting: {}", cacheKey, e.getMessage());
                    return null;
                }
            });
        } catch (Exception e) {
            log.warn("Extraction cache lookup failed, continuing without it: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The cache key is the SHA-256 of the exact bytes plus every input that could change the answer.
     * A hit therefore means "this identical file was already answered under identical conditions" -
     * never "a similar invoice was seen before".
     */
    private String cacheKey(String fileSha, String provider, String model,
                            ExtractionRequest request, long knowledgeVersion) {
        String configuration = String.join("|",
                provider,
                model == null ? "" : model,
                properties.promptVersion(),
                String.valueOf(knowledgeVersion),
                String.valueOf(request.useRag()),
                String.valueOf(request.includeTextLayer()));
        return fileSha + ":" + sha256(configuration.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    // ------------------------------------------------------------------ persistence

    private void persistSuccess(ExtractionResult result, ExtractionRequest request, String fileSha,
                                PreparedDocument document, long knowledgeVersion,
                                RetrievedKnowledge knowledge, String cacheKey) {
        try {
            String resultJson = objectMapper.writeValueAsString(result.extraction());
            String verificationJson = objectMapper.writeValueAsString(result.verification());

            ExtractionRunEntity run = new ExtractionRunEntity(result.requestId());
            run.setFileName(request.fileName());
            run.setFileSha256(fileSha);
            run.setPageCount(document.pageCount());
            run.setProvider(result.provider());
            run.setModel(result.model());
            run.setPromptVersion(properties.promptVersion());
            run.setKnowledgeVersion(knowledgeVersion);
            run.setKnowledgeIds(String.join(",", knowledge.ids()));
            run.setRagEnabled(request.useRag());
            run.setTextLayerUsed(result.textLayerUsed());
            run.setDurationMs(result.durationMs());
            run.setPromptTokens(result.usage().promptTokens());
            run.setCompletionTokens(result.usage().completionTokens());
            run.setStatus("COMPLETED");
            run.setCached(false);
            run.setResult(resultJson);
            run.setVerification(verificationJson);
            runRepository.save(run);

            if (properties.extraction().cacheEnabled()) {
                cacheRepository.save(new ExtractionCacheEntity(cacheKey, fileSha, result.provider(),
                        result.model(), properties.promptVersion(), knowledgeVersion,
                        document.pageCount(), resultJson, verificationJson));
            }
        } catch (Exception e) {
            // The extraction succeeded; failing to file the paperwork must not lose it.
            log.error("Could not persist extraction run {}: {}", result.requestId(), e.getMessage(), e);
        }
    }

    private void persistFailure(UUID requestId, ExtractionRequest request, String fileSha,
                                PreparedDocument document, ProviderHandle provider, String model,
                                long knowledgeVersion, RetrievedKnowledge knowledge,
                                boolean textLayerUsed, long durationMs, Exception error) {
        try {
            ExtractionRunEntity run = new ExtractionRunEntity(requestId);
            run.setFileName(request.fileName());
            run.setFileSha256(fileSha);
            run.setPageCount(document == null ? 0 : document.pageCount());
            run.setProvider(provider.name());
            run.setModel(model);
            run.setPromptVersion(properties.promptVersion());
            run.setKnowledgeVersion(knowledgeVersion);
            run.setKnowledgeIds(knowledge == null ? "" : String.join(",", knowledge.ids()));
            run.setRagEnabled(request.useRag());
            run.setTextLayerUsed(textLayerUsed);
            run.setDurationMs(durationMs);
            run.setStatus("FAILED");
            run.setCached(false);
            run.setErrorMessage(error.getMessage());
            runRepository.save(run);
        } catch (Exception e) {
            log.error("Could not persist failed extraction run {}: {}", requestId, e.getMessage());
        }
    }

    private JsonNode parseOrFail(String json, UUID requestId, ExtractionRequest request, String fileSha,
                                 PreparedDocument document, ProviderHandle provider, String model,
                                 long knowledgeVersion, RetrievedKnowledge knowledge,
                                 boolean textLayerUsed, long startedAt) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            ExtractionFailedException failure = new ExtractionFailedException(
                    "The model did not return parseable JSON. First 500 characters of the response: "
                            + json.substring(0, Math.min(500, json.length())), e);
            persistFailure(requestId, request, fileSha, document, provider, model, knowledgeVersion,
                    knowledge, textLayerUsed, System.currentTimeMillis() - startedAt, failure);
            throw failure;
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<ExtractionResult.KnowledgeUsed> toKnowledgeUsed(RetrievedKnowledge knowledge) {
        List<ExtractionResult.KnowledgeUsed> used = new ArrayList<>(knowledge.chunks().size());
        for (RetrievedKnowledge.Chunk chunk : knowledge.chunks()) {
            used.add(new ExtractionResult.KnowledgeUsed(
                    chunk.id(), chunk.type(), chunk.title(), chunk.mandatory(), chunk.score()));
        }
        return used;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private record ModelCall(String text, ExtractionResult.Usage usage) {
    }

    /** The model call completed but its output could not be used. Maps to HTTP 502. */
    public static class ExtractionFailedException extends RuntimeException {
        public ExtractionFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
