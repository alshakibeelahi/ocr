package com.ocr.domain.repository;

import com.ocr.domain.model.JobId;
import com.ocr.domain.model.OcrJob;

import java.util.Optional;

public interface OcrJobRepository {

    void save(OcrJob job);

    Optional<OcrJob> findById(JobId jobId);
}
