package com.ocr.v2.provider;

import com.ocr.v2.config.AiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provider resolution.
 *
 * <p>The important guarantee here is the absence of a fallback: an unavailable provider is an
 * error, never a quiet substitution of a different vendor. Which model read the document is part
 * of the audit trail, so it must be what the caller asked for or nothing at all.
 */
class ChatModelRegistryTest {

    private final ChatModelFactory factory = new ChatModelFactory();

    private AiProperties properties(String defaultProvider, Map<String, AiProperties.Provider> providers) {
        return new AiProperties(defaultProvider, "test", providers,
                new AiProperties.Embedding("ollama", "nomic-embed-text", "http://localhost:11434", null, 768),
                new AiProperties.Rag(true, 8, 0.6, 3, 0.75, 2, 0.8, 14000, false),
                new AiProperties.Extraction(true, true, 60000, 120, 2, Duration.ofSeconds(15)));
    }

    private AiProperties.Provider ollama(boolean enabled, String model) {
        return new AiProperties.Provider(enabled, ProviderType.OLLAMA, "http://localhost:11434", null, model,
                0.0, null, Duration.ofMinutes(30), 8192, "30m", "/v1/chat/completions",
                null, null, null, null, null, true, 40);
    }

    private AiProperties.Provider openAiWithoutKey() {
        return new AiProperties.Provider(true, ProviderType.OPENAI, "https://api.openai.com", null, "gpt-4.1",
                0.0, 1024, Duration.ofMinutes(10), 8192, "30m", "/v1/chat/completions",
                null, null, null, null, null, true, 20);
    }

    @Test
    @DisplayName("an enabled provider is resolvable and is used when no provider is named")
    void resolvesDefault() {
        Map<String, AiProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("ollama", ollama(true, "qwen2.5vl:3b"));

        ChatModelRegistry registry = new ChatModelRegistry(properties("ollama", providers), factory);

        assertThat(registry.availableProviders()).containsExactly("ollama");
        assertThat(registry.resolve(null).name()).isEqualTo("ollama");
        assertThat(registry.resolve("  ").name()).isEqualTo("ollama");
        assertThat(registry.resolve("OLLAMA").name()).isEqualTo("ollama");
    }

    @Test
    @DisplayName("a disabled provider is rejected with a reason, not silently replaced")
    void rejectsDisabledProvider() {
        Map<String, AiProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("ollama", ollama(true, "qwen2.5vl:3b"));
        providers.put("openai", ollama(false, "gpt-4.1"));

        ChatModelRegistry registry = new ChatModelRegistry(properties("ollama", providers), factory);

        assertThat(registry.availableProviders()).containsExactly("ollama");
        assertThatThrownBy(() -> registry.resolve("openai"))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    @DisplayName("an unknown provider names the ones that do exist")
    void rejectsUnknownProvider() {
        Map<String, AiProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("ollama", ollama(true, "qwen2.5vl:3b"));

        ChatModelRegistry registry = new ChatModelRegistry(properties("ollama", providers), factory);

        assertThatThrownBy(() -> registry.resolve("does-not-exist"))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("[ollama]");
    }

    @Test
    @DisplayName("a provider missing its API key does not break startup, only itself")
    void missingApiKeyDoesNotBreakStartup() {
        Map<String, AiProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("ollama", ollama(true, "qwen2.5vl:3b"));
        providers.put("openai", openAiWithoutKey());

        ChatModelRegistry registry = new ChatModelRegistry(properties("ollama", providers), factory);

        assertThat(registry.availableProviders()).containsExactly("ollama");
        assertThat(registry.unavailableProviders()).containsKey("openai");
        assertThat(registry.resolve(null).name()).isEqualTo("ollama");
    }

    @Test
    @DisplayName("a per-request model override wins over the configured default")
    void modelOverride() {
        Map<String, AiProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("ollama", ollama(true, "qwen2.5vl:3b"));

        ProviderHandle handle = new ChatModelRegistry(properties("ollama", providers), factory).resolve("ollama");

        assertThat(handle.modelOr(null)).isEqualTo("qwen2.5vl:3b");
        assertThat(handle.modelOr("  ")).isEqualTo("qwen2.5vl:3b");
        assertThat(handle.modelOr("llama3.2-vision:11b")).isEqualTo("llama3.2-vision:11b");
    }

    @Test
    @DisplayName("no configured providers still starts; requests then fail with a clear message")
    void noProvidersAtAll() {
        ChatModelRegistry registry = new ChatModelRegistry(properties("ollama", new LinkedHashMap<>()), factory);

        assertThat(registry.availableProviders()).isEmpty();
        assertThatThrownBy(() -> registry.resolve(null)).isInstanceOf(ProviderUnavailableException.class);
    }
}
