package com.ocr.interfaces.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "callback-auth")
public record CallbackAuthProperties(
        String tokenUrl,
        String clientId,
        String clientSecret,
        String scope,
        Duration timeout
) {
}
