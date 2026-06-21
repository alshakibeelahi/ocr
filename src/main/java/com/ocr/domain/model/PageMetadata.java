package com.ocr.domain.model;

import java.util.HashMap;
import java.util.Map;

public record PageMetadata(
        int dpi,
        boolean preprocessed,
        int width,
        int height,
        long durationMs
) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("dpi", dpi);
        map.put("preprocessed", preprocessed);
        map.put("width", width);
        map.put("height", height);
        map.put("durationMs", durationMs);
        return map;
    }
}
