package com.ocr.interfaces.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@code numThread} carries a contract the other settings do not: zero means "send no num_thread
 * option at all and let Ollama choose", so it has to survive as zero rather than being replaced by
 * a default the way {@code numPredict} and the repetition limits are.
 */
class OllamaPropertiesTest {

  private static OllamaProperties withNumThread(int numThread) {
    return new OllamaProperties(
        "http://localhost:11434", "qwen2.5vl:3b", null, null, null, 8192, 6500,
        2048, 1.1, 320, numThread, "ocr prompt", "pi prompt");
  }

  @Test
  void unsetNumThread_staysZeroSoOllamaPicksTheThreadCount() {
    assertThat(withNumThread(0).numThread()).isZero();
  }

  @Test
  void configuredNumThread_isUsedAsGiven() {
    assertThat(withNumThread(4).numThread()).isEqualTo(4);
  }

  @Test
  void negativeNumThread_isTreatedAsUnsetRatherThanSentToOllama() {
    assertThat(withNumThread(-1).numThread()).isZero();
  }

  @Test
  void otherSafetyLimits_stillFallBackToTheirDefaults() {
    OllamaProperties properties = new OllamaProperties(
        "http://localhost:11434", "qwen2.5vl:3b", null, null, null, 8192, 6500,
        0, 0, 0, 0, "ocr prompt", "pi prompt");

    assertThat(properties.numPredict()).isEqualTo(2048);
    assertThat(properties.repeatPenalty()).isEqualTo(1.1);
    assertThat(properties.repeatLastN()).isEqualTo(320);
    assertThat(properties.keepAlive()).isEqualTo("30m");
  }

  @Test
  void propertyKeys_bindFromConfiguration() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(TestConfig.class)
        .withPropertyValues(
            "ollama.base-url=http://localhost:11434",
            "ollama.model=qwen2.5vl:3b",
            "ollama.num-ctx=8192",
            "ollama.num-thread=4")
        .run(context -> {
          OllamaProperties properties = context.getBean(OllamaProperties.class);
          assertThat(properties.model()).isEqualTo("qwen2.5vl:3b");
          assertThat(properties.numThread()).isEqualTo(4);
        });
  }

  @Configuration
  @EnableConfigurationProperties(OllamaProperties.class)
  static class TestConfig {
  }
}
