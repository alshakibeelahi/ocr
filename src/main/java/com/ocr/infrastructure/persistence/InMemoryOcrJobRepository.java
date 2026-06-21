package com.ocr.infrastructure.persistence;

import com.ocr.domain.model.JobId;
import com.ocr.domain.model.OcrJob;
import com.ocr.domain.repository.OcrJobRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryOcrJobRepository implements OcrJobRepository {

    private final Map<JobId, OcrJob> jobs = new ConcurrentHashMap<>();

    @Override
    public void save(OcrJob job) {
        jobs.put(job.getJobId(), job);
    }

    @Override
    public Optional<OcrJob> findById(JobId jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }
}
