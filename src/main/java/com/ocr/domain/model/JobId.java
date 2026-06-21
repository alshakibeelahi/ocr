package com.ocr.domain.model;

import java.util.Objects;
import java.util.UUID;

public record JobId(UUID value) {

    public JobId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static JobId generate() {
        return new JobId(UUID.randomUUID());
    }

    public static JobId of(String value) {
        return new JobId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
