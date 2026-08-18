package com.ocr.v2.web;

import com.ocr.v2.knowledge.KnowledgeService;
import com.ocr.v2.pipeline.ExtractionPipeline;
import com.ocr.v2.provider.ProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.context.annotation.Profile;

import java.util.Map;

/**
 * Error mapping for the v2 endpoints.
 *
 * <p>Scoped to {@code com.ocr.v2.web} so v1's {@code GlobalExceptionHandler} keeps its exact
 * current behaviour.
 */
@RestControllerAdvice(basePackages = "com.ocr.v2.web")
@Profile("!lite")
public class V2ExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(V2ExceptionHandler.class);

    /** Unknown, disabled, or unconfigurable provider - the caller can fix this. */
    @ExceptionHandler(ProviderUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleProviderUnavailable(ProviderUnavailableException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(KnowledgeService.KnowledgeNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleKnowledgeNotFound(KnowledgeService.KnowledgeNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    /**
     * The model responded but its output was unusable. 502, not 500: the fault is upstream, and the
     * message carries the start of what came back so the operator can see why.
     */
    @ExceptionHandler(ExtractionPipeline.ExtractionFailedException.class)
    public ResponseEntity<Map<String, String>> handleExtractionFailed(ExtractionPipeline.ExtractionFailedException ex) {
        log.warn("Extraction produced unusable output: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
