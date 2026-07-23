package com.ocr.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocr.application.command.StartPiDataExtractionJobCommand;
import com.ocr.application.command.StartPiDataExtractionJobHandler;
import com.ocr.application.dto.OcrJobResponse;
import com.ocr.application.dto.StreamEvent;
import com.ocr.application.dto.StreamEventType;
import com.ocr.application.port.StreamEventPublisher;
import com.ocr.application.query.GetOcrJobHandler;
import com.ocr.application.query.GetOcrJobQuery;
import com.ocr.domain.model.JobId;
import com.ocr.infrastructure.ollama.OllamaModelVerifier;
import com.ocr.interfaces.config.OllamaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1/pi-data-extraction")
public class PiDataExtractionController {

    private static final Logger log = LoggerFactory.getLogger(PiDataExtractionController.class);
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            MediaType.APPLICATION_PDF_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            MediaType.IMAGE_JPEG_VALUE
    );

    private final StartPiDataExtractionJobHandler startJobHandler;
    private final GetOcrJobHandler getOcrJobHandler;
    private final StreamEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final OllamaModelVerifier modelVerifier;
    private final OllamaProperties ollamaProperties;

    public PiDataExtractionController(
            StartPiDataExtractionJobHandler startJobHandler,
            GetOcrJobHandler getOcrJobHandler,
            StreamEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            OllamaModelVerifier modelVerifier,
            OllamaProperties ollamaProperties
    ) {
        this.startJobHandler = startJobHandler;
        this.getOcrJobHandler = getOcrJobHandler;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.modelVerifier = modelVerifier;
        this.ollamaProperties = ollamaProperties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        OllamaModelVerifier.ModelStatus modelStatus = modelVerifier.checkStatus();
        return Map.of(
                "service", "pi-data-extraction",
                "status", modelStatus.ollamaReachable() ? "UP" : "DEGRADED",
                "ollamaReachable", modelStatus.ollamaReachable(),
                "modelReady", modelStatus.modelReady(),
                "model", ollamaProperties.model()
        );
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        OllamaModelVerifier.ModelStatus modelStatus = modelVerifier.checkStatus();
        String message = null;
        if (!modelStatus.ollamaReachable()) {
            message = "Cannot reach Ollama at " + ollamaProperties.baseUrl()
                    + ". Start it with Docker Compose or run Ollama locally.";
        } else if (!modelStatus.modelReady()) {
            message = "Model '" + ollamaProperties.model() + "' is not loaded yet. "
                    + "If using Docker, check progress with: docker logs -f ocr-ollama-init";
        }
        return Map.of(
                "ollamaReachable", modelStatus.ollamaReachable(),
                "model", ollamaProperties.model(),
                "modelReady", modelStatus.modelReady(),
                "message", message == null ? "Ready" : message
        );
    }

    @PostMapping("/jobs")
    public ResponseEntity<Map<String, String>> startJob(
            @RequestParam("file") MultipartFile file,
            @RequestParam("callbackUrl") String callbackUrl
    ) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is required"));
        }
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "callbackUrl is required"));
        }
        validateFileType(file);
        validateCallbackUrl(callbackUrl);
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "proforma-invoice.pdf";
        log.info(
                "PI extraction request accepted candidate: fileName={}, contentType={}, sizeBytes={}, callbackUrl={}",
                fileName,
                file.getContentType(),
                file.getSize(),
                callbackUrl
        );
        JobId jobId = startJobHandler.handle(new StartPiDataExtractionJobCommand(
                fileName,
                file.getBytes(),
                file.getContentType(),
                callbackUrl
        ));
        log.info("PI extraction job accepted: jobId={}, fileName={}", jobId, fileName);
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId.toString(),
                "status", "PROCESSING"
        ));
    }

    private void validateCallbackUrl(String callbackUrl) {
        URI uri = URI.create(callbackUrl);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("callbackUrl must be a valid http or https URL");
        }
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

    @GetMapping("/jobs/{jobId}")
    public OcrJobResponse getJob(@PathVariable String jobId) {
        return getOcrJobHandler.handle(new GetOcrJobQuery(jobId));
    }

    @GetMapping(value = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJob(@PathVariable String jobId) {
        SseEmitter emitter = new SseEmitter(0L);

        Consumer<StreamEvent> listener = event -> {
            try {
                String json = objectMapper.writeValueAsString(event);
                emitter.send(SseEmitter.event().data(json));
                if (event.type() == StreamEventType.JOB_COMPLETED
                        || event.type() == StreamEventType.ERROR) {
                    emitter.complete();
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        };

        eventPublisher.subscribe(jobId, listener);

        emitter.onCompletion(() -> eventPublisher.unsubscribe(jobId, listener));
        emitter.onTimeout(() -> {
            eventPublisher.unsubscribe(jobId, listener);
            emitter.complete();
        });
        emitter.onError(error -> eventPublisher.unsubscribe(jobId, listener));

        return emitter;
    }
}
