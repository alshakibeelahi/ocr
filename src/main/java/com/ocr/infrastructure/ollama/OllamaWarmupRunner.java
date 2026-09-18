package com.ocr.infrastructure.ollama;

import com.ocr.interfaces.config.OcrProperties;
import com.ocr.interfaces.config.OllamaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Loads the vision model into memory at startup.
 *
 * <p>{@code ollama.keep-alive} only keeps a model resident <em>after</em> it has been loaded once,
 * so without this the first extraction of every restart also pays the model load cost on top of
 * inference — which is what pushes that request past the client's timeout. The warm-up runs on its
 * own thread and never fails startup: Ollama being down is a runtime concern surfaced by
 * {@code /status}, not a reason to refuse to boot.
 */
@Component
public class OllamaWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OllamaWarmupRunner.class);
    private static final Duration WARMUP_TIMEOUT = Duration.ofMinutes(10);

    private final WebClient webClient;
    private final OllamaProperties ollamaProperties;
    private final OcrProperties ocrProperties;

    public OllamaWarmupRunner(
            WebClient ollamaWebClient,
            OllamaProperties ollamaProperties,
            OcrProperties ocrProperties
    ) {
        this.webClient = ollamaWebClient;
        this.ollamaProperties = ollamaProperties;
        this.ocrProperties = ocrProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!ocrProperties.isWarmupEnabled()) {
            log.info("Ollama warm-up disabled (ocr.warmup-on-startup=false)");
            return;
        }
        CompletableFuture.runAsync(this::warmUp);
    }

    private void warmUp() {
        long startMs = System.currentTimeMillis();
        log.info("Warming up Ollama model: model={}, baseUrl={}", ollamaProperties.model(), ollamaProperties.baseUrl());
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", ollamaProperties.model());
            body.put("prompt", "ok");
            body.put("stream", false);
            body.put("keep_alive", ollamaProperties.keepAlive());
            body.put("options", Map.of("num_predict", 1));

            webClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(WARMUP_TIMEOUT);

            log.info(
                    "Ollama model warm-up finished: model={}, durationMs={}",
                    ollamaProperties.model(),
                    System.currentTimeMillis() - startMs
            );
        } catch (Exception e) {
            log.warn(
                    "Ollama model warm-up failed (the first extraction will be slower): model={}, reason={}",
                    ollamaProperties.model(),
                    e.getMessage()
            );
        }
    }
}
