package com.ocr.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ocr.domain.model.OcrJob;
import com.ocr.domain.model.OcrPage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OcrJobResponse(
        String jobId,
        String fileName,
        String status,
        int totalPages,
        Instant createdAt,
        String errorMessage,
        List<PageResultDto> pages
) {

    public static OcrJobResponse from(OcrJob job) {
        List<PageResultDto> pages = job.getPages().stream()
                .map(PageResultDto::from)
                .toList();
        return new OcrJobResponse(
                job.getJobId().toString(),
                job.getFileName(),
                job.getStatus().name(),
                job.getTotalPages(),
                job.getCreatedAt(),
                job.getErrorMessage(),
                pages
        );
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record PageResultDto(
        int page,
        String status,
        String text,
        Map<String, Object> metadata,
        String errorMessage
) {

    static PageResultDto from(OcrPage page) {
        Map<String, Object> metadata = page.getMetadata() != null ? page.getMetadata().toMap() : null;
        return new PageResultDto(
                page.getPageNumber().value(),
                page.getStatus().name(),
                page.getExtractedText(),
                metadata,
                page.getErrorMessage()
        );
    }
}
