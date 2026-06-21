package com.ocr.domain.model;

public record PageNumber(int value) {

    public PageNumber {
        if (value < 1) {
            throw new IllegalArgumentException("Page number must be >= 1");
        }
    }

    public static PageNumber of(int value) {
        return new PageNumber(value);
    }
}
