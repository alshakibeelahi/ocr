package com.ocr.interfaces.web;

import com.ocr.infrastructure.ollama.OllamaModelVerifier;
import com.ocr.interfaces.config.OllamaProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ocr")
public class OcrStatusController {

    private final OllamaModelVerifier modelVerifier;
    private final OllamaProperties ollamaProperties;

    public OcrStatusController(OllamaModelVerifier modelVerifier, OllamaProperties ollamaProperties) {
        this.modelVerifier = modelVerifier;
        this.ollamaProperties = ollamaProperties;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        OllamaModelVerifier.ModelStatus modelStatus = modelVerifier.checkStatus();
        String message;
        if (!modelStatus.ollamaReachable()) {
            message = "Cannot reach Ollama at " + ollamaProperties.baseUrl()
                    + ". Start it with: docker compose up -d";
        } else if (!modelStatus.modelReady()) {
            message = "Model '" + ollamaProperties.model() + "' is not loaded yet. "
                    + "If using Docker, check progress with: docker logs -f ocr-ollama-init";
        } else {
            message = "Ready";
        }
        return Map.of(
                "ollamaReachable", modelStatus.ollamaReachable(),
                "model", ollamaProperties.model(),
                "modelReady", modelStatus.modelReady(),
                "message", message
        );
    }
}
