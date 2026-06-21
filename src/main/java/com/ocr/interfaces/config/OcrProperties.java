package com.ocr.interfaces.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ocr")
public record OcrProperties(
        int renderDpi,
        int maxImageDimension
) {
}
