package com.ocr.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class OcrJob {

    private final JobId jobId;
    private final String fileName;
    private final Instant createdAt;
    private JobStatus status;
    private int totalPages;
    private final List<OcrPage> pages;
    /** Non-fatal quality notes about the extraction, e.g. output that had to be truncated. */
    private final List<String> warnings;
    private String errorMessage;

    public OcrJob(JobId jobId, String fileName) {
        this.jobId = Objects.requireNonNull(jobId);
        this.fileName = Objects.requireNonNull(fileName);
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.pages = new ArrayList<>();
        this.warnings = new ArrayList<>();
    }

    public JobId getJobId() {
        return jobId;
    }

    public String getFileName() {
        return fileName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public JobStatus getStatus() {
        return status;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public List<OcrPage> getPages() {
        return Collections.unmodifiableList(pages);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    /** Records a quality note. A job with warnings still completes; the result is just less trusted. */
    public void addWarning(String warning) {
        if (warning != null && !warning.isBlank() && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    public void initializePages(int totalPages) {
        if (totalPages < 1) {
            throw new IllegalArgumentException("PDF must have at least one page");
        }
        this.totalPages = totalPages;
        this.pages.clear();
        for (int i = 1; i <= totalPages; i++) {
            pages.add(new OcrPage(PageNumber.of(i)));
        }
    }

    public void startProcessing() {
        this.status = JobStatus.PROCESSING;
    }

    public OcrPage getPage(PageNumber pageNumber) {
        return pages.stream()
                .filter(p -> p.getPageNumber().equals(pageNumber))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Page not found: " + pageNumber.value()));
    }

    public void complete() {
        boolean anyFailed = pages.stream().anyMatch(p -> p.getStatus() == PageStatus.FAILED);
        this.status = anyFailed ? JobStatus.FAILED : JobStatus.COMPLETED;
    }

    public void fail(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.errorMessage = errorMessage;
    }
}
