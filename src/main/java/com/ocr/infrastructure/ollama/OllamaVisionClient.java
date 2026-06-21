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
        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "stream", true,
                "options", Map.of("num_ctx", properties.numCtx()),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", properties.ocrPrompt(),
                        "images", List.of(base64Png)
                ))
        );

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
                    .timeout(properties.timeout())
                    .doOnNext(line -> parseLine(line, onChunk))
                    .blockLast(Duration.ofMinutes(10));
        } catch (WebClientResponseException e) {
            throw mapClientError(e.getStatusCode().value(), e.getResponseBodyAsString());
        }
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
                    "Page image is too large for the model context window. "
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
