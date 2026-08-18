package com.ocr.v2.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocr.v2.config.AiProperties;
import com.ocr.v2.knowledge.KnowledgeService;
import com.ocr.v2.persistence.ExtractionRunEntity;
import com.ocr.v2.persistence.ExtractionRunRepository;
import com.ocr.v2.pipeline.ExtractionListener;
import com.ocr.v2.pipeline.ExtractionPipeline;
import com.ocr.v2.pipeline.ExtractionRequest;
import com.ocr.v2.pipeline.ExtractionResult;
import com.ocr.v2.provider.ChatModelRegistry;
import com.ocr.v2.provider.ProviderHandle;
import com.ocr.v2.web.dto.ExtractResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synchronous, RAG-grounded PI extraction.
 *
 * <p>No jobs, no polling, no callback: the caller posts a document and gets the answer on the same
 * request, the way a chat works. Two flavours:
 *
 * <ul>
 *   <li>{@code POST /extract} - one JSON response when the extraction finishes. No timeout is
 *       applied to the request.
 *   <li>{@code POST /extract/stream} - server-sent events: stage updates, then the model's output
 *       as it is generated, then the verified result. Preferred behind any reverse proxy, because
 *       the connection is never idle.
 * </ul>
 */
@CrossOrigin(origins = "*")
@RestController
@Profile("!lite")
@RequestMapping("/api/v2/pi-extraction")
public class PiExtractionV2Controller {

    private static final Logger log = LoggerFactory.getLogger(PiExtractionV2Controller.class);

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            MediaType.APPLICATION_PDF_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            MediaType.IMAGE_JPEG_VALUE);

    private final ExtractionPipeline pipeline;
    private final ChatModelRegistry providers;
    private final KnowledgeService knowledgeService;
    private final ExtractionRunRepository runRepository;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor aiTaskExecutor;
    private final ScheduledExecutorService heartbeatScheduler;

    public PiExtractionV2Controller(ExtractionPipeline pipeline,
                                    ChatModelRegistry providers,
                                    KnowledgeService knowledgeService,
                                    ExtractionRunRepository runRepository,
                                    AiProperties properties,
                                    ObjectMapper objectMapper,
                                    @Qualifier("aiTaskExecutor") Executor aiTaskExecutor,
                                    @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.pipeline = pipeline;
        this.providers = providers;
        this.knowledgeService = knowledgeService;
        this.runRepository = runRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.aiTaskExecutor = aiTaskExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    // ------------------------------------------------------------------ extract (blocking)

    /**
     * Blocking extraction.
     *
     * <p>Returned as a {@link DeferredResult} with {@code Long.MAX_VALUE} so Spring's async
     * request timeout never fires: a large scan on a CPU-only Ollama can legitimately take many
     * minutes and cutting it off would waste the work already done. Intermediaries have their own
     * idle timeouts though - behind nginx, prefer {@code /extract/stream}.
     */
    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DeferredResult<ResponseEntity<?>> extract(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "useRag", defaultValue = "true") boolean useRag,
            @RequestParam(value = "includeTextLayer", defaultValue = "true") boolean includeTextLayer,
            @RequestParam(value = "useCache", defaultValue = "true") boolean useCache) throws IOException {

        ExtractionRequest request = toRequest(file, provider, model, useRag, includeTextLayer, useCache);
        DeferredResult<ResponseEntity<?>> deferred = new DeferredResult<>(Long.MAX_VALUE);

        aiTaskExecutor.execute(() -> {
            try {
                ExtractionResult result = pipeline.extract(request, ExtractionListener.NOOP);
                deferred.setResult(ResponseEntity.ok(ExtractResponse.from(result)));
            } catch (Exception e) {
                log.error("v2 extraction failed for {}: {}", request.fileName(), e.getMessage(), e);
                deferred.setErrorResult(e);
            }
        });
        return deferred;
    }

    // ------------------------------------------------------------------ extract (streaming)

    /**
     * Streaming extraction. Event names: {@code stage}, {@code delta}, {@code result}, {@code error}.
     *
     * <p>A comment heartbeat goes out every {@code ai.extraction.heartbeat-interval} so the
     * connection is never idle even while the model is thinking, which is what keeps proxies and
     * load balancers from closing it mid-extraction.
     */
    @PostMapping(value = "/extract/stream",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter extractStream(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "useRag", defaultValue = "true") boolean useRag,
            @RequestParam(value = "includeTextLayer", defaultValue = "true") boolean includeTextLayer,
            @RequestParam(value = "useCache", defaultValue = "true") boolean useCache,
            @RequestParam(value = "streamDeltas", defaultValue = "true") boolean streamDeltas) throws IOException {

        ExtractionRequest request = toRequest(file, provider, model, useRag, includeTextLayer, useCache);

        // 0L = no timeout. The heartbeat below, not a timeout, is what keeps the connection healthy.
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean finished = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = startHeartbeat(emitter, finished);

        emitter.onCompletion(() -> stopHeartbeat(heartbeat, finished));
        emitter.onError(error -> stopHeartbeat(heartbeat, finished));
        emitter.onTimeout(() -> {
            stopHeartbeat(heartbeat, finished);
            emitter.complete();
        });

        aiTaskExecutor.execute(() -> {
            try {
                ExtractionResult result = pipeline.extract(request, new SseListener(emitter, streamDeltas));
                send(emitter, "result", ExtractResponse.from(result));
                emitter.complete();
            } catch (Exception e) {
                log.error("v2 streaming extraction failed for {}: {}", request.fileName(), e.getMessage(), e);
                send(emitter, "error", Map.of(
                        "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                emitter.complete();
            } finally {
                stopHeartbeat(heartbeat, finished);
            }
        });

        return emitter;
    }

    // ------------------------------------------------------------------ introspection

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean anyProvider = !providers.availableProviders().isEmpty();
        long knowledgeCount;
        long indexedCount;
        boolean knowledgeReachable;
        try {
            knowledgeCount = knowledgeService.enabledCount();
            indexedCount = knowledgeService.indexedCount();
            knowledgeReachable = true;
        } catch (Exception e) {
            knowledgeCount = 0;
            indexedCount = 0;
            knowledgeReachable = false;
        }

        // Two silent-degradation modes to catch: an empty knowledge base, and one that exists in
        // the table but is missing from the vector index. Either leaves extraction running with
        // less guidance than intended, which is worth reporting rather than discovering later.
        boolean knowledgeReady = knowledgeReachable && knowledgeCount > 0 && indexedCount >= knowledgeCount;

        body.put("service", "pi-extraction-v2");
        body.put("status", anyProvider && knowledgeReady ? "UP" : "DEGRADED");
        body.put("defaultProvider", providers.defaultProvider());
        body.put("availableProviders", providers.availableProviders());
        body.put("unavailableProviders", providers.unavailableProviders());
        body.put("knowledgeStoreReachable", knowledgeReachable);
        body.put("knowledgeDocuments", knowledgeCount);
        body.put("knowledgeIndexed", indexedCount);
        body.put("embeddingModel", properties.embedding().model());
        body.put("embeddingDimensions", properties.embedding().dimensions());
        body.put("promptVersion", properties.promptVersion());
        body.put("ragEnabled", properties.rag().enabled());
        return body;
    }

    @GetMapping("/providers")
    public Map<String, Object> providers() {
        Map<String, Object> configured = new LinkedHashMap<>();
        providers.handles().forEach((name, handle) -> configured.put(name, providerInfo(handle)));
        return Map.of(
                "default", providers.defaultProvider(),
                "available", configured,
                "unavailable", providers.unavailableProviders());
    }

    private Map<String, Object> providerInfo(ProviderHandle handle) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("type", handle.type().name());
        info.put("model", handle.defaultModel());
        info.put("maxImages", handle.maxImages());
        info.put("nativeJsonMode", handle.nativeJsonMode());
        info.put("timeout", handle.timeout().toString());
        return info;
    }

    /** Audit record for a past extraction: which model, which rules, which checks. */
    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String id) {
        return runRepository.findById(UUID.fromString(id))
                .map(run -> ResponseEntity.ok(toRunView(run)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> toRunView(ExtractionRunEntity run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("requestId", run.getId().toString());
        view.put("createdAt", run.getCreatedAt());
        view.put("fileName", run.getFileName());
        view.put("fileSha256", run.getFileSha256());
        view.put("pageCount", run.getPageCount());
        view.put("provider", run.getProvider());
        view.put("model", run.getModel());
        view.put("promptVersion", run.getPromptVersion());
        view.put("knowledgeVersion", run.getKnowledgeVersion());
        view.put("knowledgeIds", run.getKnowledgeIds() == null || run.getKnowledgeIds().isBlank()
                ? java.util.List.of() : java.util.List.of(run.getKnowledgeIds().split(",")));
        view.put("ragEnabled", run.isRagEnabled());
        view.put("textLayerUsed", run.isTextLayerUsed());
        view.put("durationMs", run.getDurationMs());
        view.put("status", run.getStatus());
        view.put("promptTokens", run.getPromptTokens());
        view.put("completionTokens", run.getCompletionTokens());
        view.put("extraction", readTree(run.getResult()));
        view.put("verification", readTree(run.getVerification()));
        if (run.getErrorMessage() != null) {
            view.put("errorMessage", run.getErrorMessage());
        }
        return view;
    }

    private Object readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return json;
        }
    }

    // ------------------------------------------------------------------ helpers

    private ExtractionRequest toRequest(MultipartFile file, String provider, String model,
                                        boolean useRag, boolean includeTextLayer, boolean useCache)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        validateFileType(file);
        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "proforma-invoice.pdf";
        log.info("v2 extraction request: fileName={}, contentType={}, sizeBytes={}, provider={}, useRag={}",
                fileName, file.getContentType(), file.getSize(), provider == null ? "(default)" : provider, useRag);
        return new ExtractionRequest(fileName, file.getBytes(), file.getContentType(),
                provider, model, useRag, includeTextLayer, useCache);
    }

    private void validateFileType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && ALLOWED_CONTENT_TYPES.contains(contentType)) {
            return;
        }
        String fileName = file.getOriginalFilename();
        if (fileName != null) {
            String normalized = fileName.toLowerCase();
            if (normalized.endsWith(".pdf") || normalized.endsWith(".png")
                    || normalized.endsWith(".jpg") || normalized.endsWith(".jpeg")) {
                return;
            }
        }
        throw new IllegalArgumentException("Only PDF, PNG, and JPG/JPEG files are supported");
    }

    private ScheduledFuture<?> startHeartbeat(SseEmitter emitter, AtomicBoolean finished) {
        long seconds = Math.max(1, properties.extraction().heartbeatInterval().toSeconds());
        return heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (finished.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception e) {
                // Client went away; the pipeline finishes and completes the emitter itself.
                finished.set(true);
            }
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    private void stopHeartbeat(ScheduledFuture<?> heartbeat, AtomicBoolean finished) {
        finished.set(true);
        heartbeat.cancel(false);
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data),
                    MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("Could not send SSE event '{}': {}", event, e.getMessage());
        }
    }

    /** Turns pipeline progress into SSE events. */
    private final class SseListener implements ExtractionListener {

        private final SseEmitter emitter;
        private final boolean streamDeltas;

        private SseListener(SseEmitter emitter, boolean streamDeltas) {
            this.emitter = emitter;
            this.streamDeltas = streamDeltas;
        }

        @Override
        public void onStage(String stage, String message) {
            send(emitter, "stage", Map.of("stage", stage, "message", message));
        }

        @Override
        public void onDelta(String text) {
            if (streamDeltas) {
                send(emitter, "delta", Map.of("text", text));
            }
        }
    }
}
