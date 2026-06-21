package com.ocr.interfaces.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ollama")
public record OllamaProperties(
        String baseUrl,
        String model,
        Duration timeout,
        int numCtx,
        String ocrPrompt
) {
}
