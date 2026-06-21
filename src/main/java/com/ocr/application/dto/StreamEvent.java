package com.ocr.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamEvent(
        StreamEventType type,
        String jobId,
        Integer page,
        String chunk,
        String text,
        Map<String, Object> metadata,
        Integer totalPages,
        String fileName,
        String status,
        String message,
        Object job,
        Instant timestamp
) {

    public static StreamEvent jobStarted(String jobId, String fileName, int totalPages) {
        return new StreamEvent(
                StreamEventType.JOB_STARTED,
                jobId,
                null,
                null,
                null,
                null,
                totalPages,
                fileName,
                "PROCESSING",
                null,
                null,
                Instant.now()
        );
    }

    public static StreamEvent pageStarted(String jobId, int page, int totalPages) {
        return new StreamEvent(
                StreamEventType.PAGE_STARTED,
                jobId,
                page,
                null,
                null,
                null,
                totalPages,
                null,
                null,
                null,
                null,
                Instant.now()
        );
    }

    public static StreamEvent pageChunk(String jobId, int page, String chunk) {
        return new StreamEvent(
                StreamEventType.PAGE_CHUNK,
                jobId,
                page,
                chunk,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now()
        );
    }

    public static StreamEvent pageCompleted(String jobId, int page, String text, Map<String, Object> metadata) {
        return new StreamEvent(
                StreamEventType.PAGE_COMPLETED,
                jobId,
                page,
                null,
                text,
                metadata,
                null,
                null,
                null,
                null,
                null,
                Instant.now()
        );
    }

    public static StreamEvent jobCompleted(String jobId, Object job) {
        return new StreamEvent(
                StreamEventType.JOB_COMPLETED,
                jobId,
                null,
                null,
                null,
                null,
                null,
                null,
                "COMPLETED",
                null,
                job,
                Instant.now()
        );
    }

    public static StreamEvent error(String jobId, String message, Integer page) {
        return new StreamEvent(
                StreamEventType.ERROR,
                jobId,
                page,
                null,
                null,
                null,
                null,
                null,
                "FAILED",
                message,
                null,
                Instant.now()
        );
    }
}
