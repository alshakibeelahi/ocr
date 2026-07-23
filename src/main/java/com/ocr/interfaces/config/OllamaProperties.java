package com.ocr.interfaces.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ollama")
public record OllamaProperties(
        String baseUrl,
        String model,
        Duration timeout,
        Duration requestTimeout,
        String keepAlive,
        int numCtx,
        int maxPromptChars,
        String ocrPrompt,
        String piDataExtractionPrompt
) {

    public Duration requestTimeout() {
        return requestTimeout != null ? requestTimeout : Duration.ofMinutes(30);
    }

    public String keepAlive() {
        return keepAlive == null || keepAlive.isBlank() ? "30m" : keepAlive;
    }
}
