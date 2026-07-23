package com.ocr.domain.model;

import java.util.Objects;

public class OcrPage {

    private final PageNumber pageNumber;
    private PageStatus status;
    private String extractedText;
    private PageMetadata metadata;
    private String errorMessage;

    public OcrPage(PageNumber pageNumber) {
        this.pageNumber = Objects.requireNonNull(pageNumber);
        this.status = PageStatus.PENDING;
        this.extractedText = "";
    }

    public PageNumber getPageNumber() {
        return pageNumber;
    }

    public int getPageIndex() {
        return pageNumber.value() - 1;
    }

    public PageStatus getStatus() {
        return status;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public PageMetadata getMetadata() {
        return metadata;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void startProcessing() {
        this.status = PageStatus.PROCESSING;
        this.extractedText = "";
        this.metadata = null;
        this.errorMessage = null;
    }

    public void appendText(String chunk) {
        if (chunk != null && !chunk.isEmpty()) {
            this.extractedText += chunk;
        }
    }

    public void replaceText(String text) {
        this.extractedText = text != null ? text : "";
    }

    public void complete(PageMetadata metadata) {
        this.status = PageStatus.COMPLETED;
        this.metadata = metadata;
    }

    public void fail(String errorMessage) {
        this.status = PageStatus.FAILED;
        this.errorMessage = errorMessage;
    }
}
