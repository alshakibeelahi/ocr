package com.ocr.infrastructure.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocr.interfaces.config.OllamaProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class OllamaModelVerifier {

    private final WebClient webClient;
    private final OllamaProperties properties;
    private final ObjectMapper objectMapper;

    public OllamaModelVerifier(WebClient ollamaWebClient, OllamaProperties properties, ObjectMapper objectMapper) {
        this.webClient = ollamaWebClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ModelStatus checkStatus() {
        try {
            String response = webClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (response == null) {
                return new ModelStatus(true, false);
            }
            JsonNode models = objectMapper.readTree(response).path("models");
            if (!models.isArray()) {
                return new ModelStatus(true, false);
            }
            String required = properties.model();
            for (JsonNode model : models) {
                String name = model.path("name").asText();
                if (name.equals(required) || name.startsWith(required + ":")) {
                    return new ModelStatus(true, true);
                }
            }
            return new ModelStatus(true, false);
        } catch (Exception e) {
            return new ModelStatus(false, false);
        }
    }

    public record ModelStatus(boolean ollamaReachable, boolean modelReady) {
    }
}
