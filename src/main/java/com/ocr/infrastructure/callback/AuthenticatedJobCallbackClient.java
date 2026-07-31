package com.ocr.infrastructure.callback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocr.application.dto.OcrJobResponse;
import com.ocr.application.port.JobCallbackPort;
import com.ocr.interfaces.config.CallbackAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AuthenticatedJobCallbackClient implements JobCallbackPort {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedJobCallbackClient.class);

    private final WebClient.Builder webClientBuilder;
    private final CallbackAuthProperties properties;
    private final ObjectMapper objectMapper;

    public AuthenticatedJobCallbackClient(
            WebClient.Builder webClientBuilder,
            CallbackAuthProperties properties,
            ObjectMapper objectMapper
    ) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void postJobResult(String callbackUrl, OcrJobResponse jobResponse) {
        URI callbackUri = validateCallbackUrl(callbackUrl);
        log.info("Starting callback delivery: jobId={}, status={}, callbackUrl={}", jobResponse.jobId(), jobResponse.status(), callbackUri);
        Map<String, Object> payload = buildPayload(jobResponse);
        Duration timeout = properties.timeout() != null ? properties.timeout() : Duration.ofSeconds(30);
        log.info("Posting callback payload: jobId={}, status={}, hasExtraction={}", jobResponse.jobId(), jobResponse.status(), payload.containsKey("extraction"));

        webClientBuilder.build()
                .post()
                .uri(callbackUri)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (properties.authRequired()) {
                        headers.setBearerAuth(fetchAccessToken());
                    }
                })
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .block(timeout);

        log.info("Posted OCR callback for job {} to {}", jobResponse.jobId(), callbackUri);
    }

    private String fetchAccessToken() {
        if (isBlank(properties.tokenUrl()) || isBlank(properties.clientId()) || isBlank(properties.clientSecret())) {
            throw new IllegalStateException(
                    "Callback auth is not fully configured. Set callback-auth.token-url, client-id, and client-secret."
            );
        }

        Duration timeout = properties.timeout() != null ? properties.timeout() : Duration.ofSeconds(30);
        log.info("Fetching callback access token: tokenUrl={}, clientId={}", properties.tokenUrl(), properties.clientId());
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "client_credentials");
        formData.add("client_id", properties.clientId());
        formData.add("client_secret", properties.clientSecret());
        if (!isBlank(properties.scope())) {
            formData.add("scope", properties.scope());
        }

        JsonNode tokenResponse = webClientBuilder.build()
                .post()
                .uri(properties.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(timeout);

        String accessToken = tokenResponse != null ? tokenResponse.path("access_token").asText("") : "";
        if (accessToken.isBlank()) {
            throw new IllegalStateException("Token endpoint response did not include access_token");
        }
        log.info("Fetched callback access token successfully for clientId={}", properties.clientId());
        return accessToken;
    }

    private Map<String, Object> buildPayload(OcrJobResponse jobResponse) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", jobResponse.jobId());
        payload.put("status", jobResponse.status());

        if ("COMPLETED".equals(jobResponse.status())) {
            payload.put("extraction", extractJson(jobResponse));
        }
        if (jobResponse.errorMessage() != null && !jobResponse.errorMessage().isBlank()) {
            payload.put("errorMessage", jobResponse.errorMessage());
        }

        return payload;
    }

    private Object extractJson(OcrJobResponse jobResponse) {
        String text = jobResponse.firstPageText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            log.warn("OCR extraction for job {} is not valid JSON, sending raw text", jobResponse.jobId(), e);
            return text;
        }
    }

    private URI validateCallbackUrl(String callbackUrl) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            throw new IllegalArgumentException("callbackUrl is required");
        }
        URI uri = URI.create(callbackUrl);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("callbackUrl must be a valid http or https URL");
        }
        return uri;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
