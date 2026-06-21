package com.ocr.application.dto;

public enum StreamEventType {
    JOB_STARTED,
    PAGE_STARTED,
    PAGE_CHUNK,
    PAGE_COMPLETED,
    JOB_COMPLETED,
    ERROR
}
