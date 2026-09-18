package com.ocr.interfaces.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rendering and sizing limits for the OCR pipeline.
 *
 * <p>{@code maxPages} exists because every page of a document is sent to the vision model in a
 * single chat request. Each rendered page costs roughly 1.5k-2.5k vision tokens, so an uncapped
 * multi-page PDF overflows {@code ollama.num-ctx} and the model either returns
 * {@code exceed_context_size_error} or silently truncates and emits broken JSON.
 */
@ConfigurationProperties(prefix = "ocr")
public record OcrProperties(
        int renderDpi,
        int maxImageDimension,
        int maxPages,
        Boolean warmupOnStartup
) {

  public int renderDpi() {
    return renderDpi > 0 ? renderDpi : 150;
  }

  public int maxImageDimension() {
    return maxImageDimension > 0 ? maxImageDimension : 1024;
  }

  /** Hard cap on how many pages of one document are sent to the model. */
  public int maxPages() {
    return maxPages > 0 ? maxPages : 3;
  }

  /** Whether to load the model into memory at startup so the first real request is not slow. */
  public boolean isWarmupEnabled() {
    return warmupOnStartup == null || warmupOnStartup;
  }
}
