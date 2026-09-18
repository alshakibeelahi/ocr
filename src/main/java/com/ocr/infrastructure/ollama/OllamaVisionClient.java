package com.ocr.infrastructure.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocr.application.port.OllamaVisionPort;
import com.ocr.interfaces.config.OllamaProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class OllamaVisionClient implements OllamaVisionPort {

    private final WebClient webClient;
    private final OllamaProperties properties;
    private final ObjectMapper objectMapper;

    public OllamaVisionClient(WebClient ollamaWebClient, OllamaProperties properties, ObjectMapper objectMapper) {
        this.webClient = ollamaWebClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void streamOcr(String base64Png, Consumer<String> onChunk) {
        streamVision(base64Png, properties.ocrPrompt(), onChunk);
    }

    @Override
    public void streamVision(String base64Png, String prompt, Consumer<String> onChunk) {
        streamVision(base64Png, prompt, false, onChunk);
    }

    @Override
    public void streamVision(String base64Png, String prompt, boolean jsonMode, Consumer<String> onChunk) {
        streamVision(List.of(base64Png), prompt, jsonMode, onChunk);
    }

    @Override
    public void streamVision(List<String> base64Pngs, String prompt, boolean jsonMode, Consumer<String> onChunk) {
        String promptWithinLimit = requirePromptWithinLimit(prompt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("stream", true);
        // Keep the model loaded between requests so each job does not pay the load cost again.
        body.put("keep_alive", properties.keepAlive());
        if (jsonMode) {
            body.put("format", "json");
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("num_ctx", properties.numCtx());
        // Greedy decoding keeps extraction reproducible, but on its own it also makes a repetition
        // loop permanent: the repeated phrase stays the most likely continuation of itself. The
        // penalty breaks that, and num_predict bounds the cost when it does not.
        options.put("temperature", 0);
        options.put("num_predict", properties.numPredict());
        options.put("repeat_penalty", properties.repeatPenalty());
        options.put("repeat_last_n", properties.repeatLastN());
        body.put("options", options);
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", promptWithinLimit,
                "images", base64Pngs
        )));

        // ollama.timeout is an IDLE timeout: it only fires when no stream signal arrives for that
        // long (e.g. slow CPU image decode produces no tokens). requestTimeout caps the whole call.
        Duration idleTimeout = properties.timeout() != null ? properties.timeout() : Duration.ofMinutes(10);
        Duration overallTimeout = properties.requestTimeout() != null
                ? properties.requestTimeout()
                : idleTimeout.multipliedBy(3);

        try {
            Flux<String> responseFlux = webClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_NDJSON, MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(bodyText -> Mono.error(mapClientError(response.statusCode().value(), bodyText))))
                    .bodyToFlux(String.class);

            responseFlux
                    .timeout(idleTimeout, Mono.error(() -> new IllegalStateException(
                            "Ollama produced no output for " + idleTimeout
                                    + ". The model is likely still decoding the image on CPU. "
                                    + "Run Ollama on GPU, raise ollama.timeout, or lower ocr.max-image-dimension."
                    )))
                    .doOnNext(line -> parseLine(line, onChunk))
                    .blockLast(overallTimeout);
        } catch (WebClientResponseException e) {
            throw mapClientError(e.getStatusCode().value(), e.getResponseBodyAsString());
        }
    }

    private String requirePromptWithinLimit(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Ollama prompt must not be blank");
        }
        int maxPromptChars = properties.maxPromptChars();
        if (maxPromptChars > 0 && prompt.length() > maxPromptChars) {
            throw new IllegalArgumentException(
                    "Ollama prompt exceeds max prompt limit of " + maxPromptChars + " characters"
            );
        }
        return prompt;
    }

    private IllegalStateException mapClientError(int status, String body) {
        if (status == 404) {
            return new IllegalStateException(
                    "Ollama model '" + properties.model() + "' is not available. "
                            + "Wait for the model download to finish (docker logs -f ocr-ollama-init), "
                            + "or run: ollama pull " + properties.model()
            );
        }
        if (status == 400 && body != null && body.contains("exceed_context_size_error")) {
            return new IllegalStateException(
                    "Document image payload is too large for the model context window. "
                            + "Lower ocr.max-image-dimension or raise ollama.num-ctx in application.yml."
            );
        }
        String detail = body == null || body.isBlank() ? "" : " Response: " + body.trim();
        return new IllegalStateException("Ollama request failed (HTTP " + status + ")." + detail);
    }

    private void parseLine(String line, Consumer<String> onChunk) {
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(line);
            JsonNode message = root.path("message");
            if (message.hasNonNull("content")) {
                String content = message.get("content").asText();
                if (!content.isEmpty()) {
                    onChunk.accept(content);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Ollama stream response: " + line, e);
        }
    }
}
