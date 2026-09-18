package com.ocr.interfaces.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * These defaults are load-bearing rather than cosmetic: an unset or zero {@code maxPages} sending
 * every page of a document into one vision request is what overflows the model's context window,
 * and warm-up defaulting to off would put the model load cost back on the first real extraction.
 */
class OcrPropertiesTest {

  @Test
  void unsetValues_fallBackToTheSafeDefaults() {
    OcrProperties properties = new OcrProperties(0, 0, 0, null);

    assertThat(properties.renderDpi()).isEqualTo(150);
    assertThat(properties.maxImageDimension()).isEqualTo(1024);
    assertThat(properties.maxPages()).isEqualTo(3);
    assertThat(properties.isWarmupEnabled()).isTrue();
  }

  @Test
  void configuredValues_areUsedAsGiven() {
    OcrProperties properties = new OcrProperties(300, 2048, 8, Boolean.FALSE);

    assertThat(properties.renderDpi()).isEqualTo(300);
    assertThat(properties.maxImageDimension()).isEqualTo(2048);
    assertThat(properties.maxPages()).isEqualTo(8);
    assertThat(properties.isWarmupEnabled()).isFalse();
  }

  @Test
  void negativeValues_areTreatedAsUnsetRatherThanHonoured() {
    OcrProperties properties = new OcrProperties(-1, -1, -1, null);

    assertThat(properties.renderDpi()).isEqualTo(150);
    assertThat(properties.maxImageDimension()).isEqualTo(1024);
    assertThat(properties.maxPages()).isEqualTo(3);
  }

  @Test
  void propertyKeys_bindFromConfiguration() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(TestConfig.class)
        .withPropertyValues(
            "ocr.render-dpi=200",
            "ocr.max-image-dimension=1536",
            "ocr.max-pages=5",
            "ocr.warmup-on-startup=false")
        .run(context -> {
          OcrProperties properties = context.getBean(OcrProperties.class);
          assertThat(properties.renderDpi()).isEqualTo(200);
          assertThat(properties.maxImageDimension()).isEqualTo(1536);
          assertThat(properties.maxPages()).isEqualTo(5);
          assertThat(properties.isWarmupEnabled()).isFalse();
        });
  }

  @Configuration
  @EnableConfigurationProperties(OcrProperties.class)
  static class TestConfig {
  }
}
