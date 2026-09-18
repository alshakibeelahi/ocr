package com.ocr.interfaces.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Connection and sampling settings for the vision model.
 *
 * <p>The sampling controls are not tuning knobs, they are safety limits. Greedy decoding
 * (temperature 0) is what makes extraction reproducible, but on a small model it also makes a
 * repetition loop self-sustaining: once the model starts repeating a phrase, the most likely next
 * token is the same phrase again, forever. Left unbounded it filled a 30 minute request with one
 * address repeated 141 times. {@code repeatPenalty} breaks that loop and {@code numPredict} caps
 * what it can cost when it is not broken.
 */
@ConfigurationProperties(prefix = "ollama")
public record OllamaProperties(
        String baseUrl,
        String model,
        Duration timeout,
        Duration requestTimeout,
        String keepAlive,
        int numCtx,
        int maxPromptChars,
        int numPredict,
        double repeatPenalty,
        int repeatLastN,
        String ocrPrompt,
        String piDataExtractionPrompt
) {

    public Duration requestTimeout() {
        return requestTimeout != null ? requestTimeout : Duration.ofMinutes(30);
    }

    public String keepAlive() {
        return keepAlive == null || keepAlive.isBlank() ? "30m" : keepAlive;
    }

    /**
     * Hard ceiling on generated tokens. A complete extraction for a one page invoice runs to
     * roughly 800-1500 tokens, so this leaves ample headroom while still bounding a runaway.
     */
    public int numPredict() {
        return numPredict > 0 ? numPredict : 2048;
    }

    /** Above 1.0 discourages reusing recent tokens. 1.1 is enough to break a loop without
     *  distorting the verbatim strings the prompt asks to preserve. */
    public double repeatPenalty() {
        return repeatPenalty > 0 ? repeatPenalty : 1.1;
    }

    /** How far back the penalty looks. Must exceed the length of a repeating unit to catch it. */
    public int repeatLastN() {
        return repeatLastN > 0 ? repeatLastN : 320;
    }
}
