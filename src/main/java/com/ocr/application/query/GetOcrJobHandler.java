package com.ocr.application.query;

import com.ocr.application.dto.OcrJobResponse;
import com.ocr.domain.model.JobId;
import com.ocr.domain.repository.OcrJobRepository;
import org.springframework.stereotype.Service;

@Service
public class GetOcrJobHandler {

    private final OcrJobRepository repository;

    public GetOcrJobHandler(OcrJobRepository repository) {
        this.repository = repository;
    }

    public OcrJobResponse handle(GetOcrJobQuery query) {
        JobId jobId = JobId.of(query.jobId());
        return repository.findById(jobId)
                .map(OcrJobResponse::from)
                .orElseThrow(() -> new JobNotFoundException(query.jobId()));
    }

    public static class JobNotFoundException extends RuntimeException {
        public JobNotFoundException(String jobId) {
            super("OCR job not found: " + jobId);
        }
    }
}
