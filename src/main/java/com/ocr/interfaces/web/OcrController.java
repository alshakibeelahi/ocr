package com.ocr.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocr.application.command.StartOcrJobCommand;
import com.ocr.application.command.StartOcrJobHandler;
import com.ocr.application.dto.OcrJobResponse;
import com.ocr.application.dto.StreamEvent;
import com.ocr.application.dto.StreamEventType;
import com.ocr.application.port.StreamEventPublisher;
import com.ocr.application.query.GetOcrJobHandler;
import com.ocr.application.query.GetOcrJobQuery;
import com.ocr.domain.model.JobId;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/ocr")
public class OcrController {

    private final StartOcrJobHandler startOcrJobHandler;
    private final GetOcrJobHandler getOcrJobHandler;
    private final StreamEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public OcrController(
            StartOcrJobHandler startOcrJobHandler,
            GetOcrJobHandler getOcrJobHandler,
            StreamEventPublisher eventPublisher,
            ObjectMapper objectMapper
    ) {
        this.startOcrJobHandler = startOcrJobHandler;
        this.getOcrJobHandler = getOcrJobHandler;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/jobs")
    public ResponseEntity<Map<String, String>> startJob(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is required"));
        }
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.pdf";
        JobId jobId = startOcrJobHandler.handle(new StartOcrJobCommand(fileName, file.getBytes()));
        return ResponseEntity.accepted().body(Map.of("jobId", jobId.toString()));
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
