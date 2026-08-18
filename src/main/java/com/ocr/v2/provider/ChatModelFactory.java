package com.ocr.v2.provider;

import com.ocr.v2.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import com.ocr.v2.config.V2Component;
import org.springframework.util.ClassUtils;

/**
 * Builds one {@link ProviderHandle} per enabled entry under {@code ai.providers.*}.
 *
 * <p>Models are constructed explicitly rather than through the Spring AI starters'
 * auto-configuration ({@code spring.ai.model.chat=none} in application.yml turns that off). Three
 * reasons: several providers must coexist so a request can pick one; a missing API key must not
 * break application startup, only that one provider; and the same {@code OPENAI} client is reused
 * for Groq/DeepSeek/Mistral by pointing it at a different base-url.
 *
 * <p>The cloud vendors (Azure, Vertex, Bedrock) are constructed in nested holder classes so that
 * dropping those Maven dependencies for a lean build degrades to "provider unavailable" instead of
 * a {@link NoClassDefFoundError}.
 */
@V2Component
public class ChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatModelFactory.class);

    private static final String AZURE_CLIENT = "com.azure.ai.openai.OpenAIClientBuilder";
    private static final String VERTEX_CLIENT = "com.google.cloud.vertexai.VertexAI";
    private static final String BEDROCK_CLIENT = "software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient";

    /**
     * @return the built handle, or {@code null} when the provider cannot be constructed (missing
     *         credentials, missing optional dependency). Never throws: one broken provider must not
     *         prevent the application from starting with the others.
     */
    public ProviderHandle create(String name, AiProperties.Provider config) {
        try {
            if (config.type() == null) {
                throw new IllegalStateException("ai.providers." + name + ".type is required; expected one of "
                        + java.util.Arrays.toString(ProviderType.values()));
            }
            return switch (config.type()) {
                case OLLAMA -> ollama(name, config);
                case OPENAI -> openAi(name, config);
                case ANTHROPIC -> anthropic(name, config);
                case AZURE_OPENAI -> present(AZURE_CLIENT, name) ? Azure.build(name, config) : null;
                case VERTEX_GEMINI -> present(VERTEX_CLIENT, name) ? Vertex.build(name, config) : null;
                case BEDROCK -> present(BEDROCK_CLIENT, name) ? Bedrock.build(name, config) : null;
            };
        } catch (Exception e) {
            log.warn("Chat provider '{}' ({}) could not be initialised and will be unavailable: {}",
                    name, config.type(), e.getMessage());
            return null;
        }
    }

    private boolean present(String className, String providerName) {
        boolean present = ClassUtils.isPresent(className, ChatModelFactory.class.getClassLoader());
        if (!present) {
            log.info("Provider '{}' is enabled but its vendor SDK is not on the classpath - skipping.", providerName);
        }
        return present;
    }

    // ------------------------------------------------------------------ Ollama

    private ProviderHandle ollama(String name, AiProperties.Provider config) {
        OllamaApi api = OllamaApi.builder()
                .baseUrl(require(config.baseUrl(), name, "base-url"))
                .build();
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(ollamaOptions(config, config.model(), true))
                .build();
        return new ProviderHandle(name, ProviderType.OLLAMA, config.model(), config.maxImages(),
                config.jsonMode(), config.timeout(), model,
                (modelOverride, json) -> ollamaOptions(config, or(modelOverride, config.model()), json));
    }

    private OllamaChatOptions ollamaOptions(AiProperties.Provider config, String model, boolean jsonMode) {
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
                .model(model)
                .temperature(config.temperature())
                .numCtx(config.numCtx())
                .keepAlive(config.keepAlive());
        if (jsonMode) {
            builder.format("json");
        }
        if (config.maxTokens() != null) {
            builder.numPredict(config.maxTokens());
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ OpenAI-compatible

    private ProviderHandle openAi(String name, AiProperties.Provider config) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(require(config.baseUrl(), name, "base-url"))
                .apiKey(require(config.apiKey(), name, "api-key"))
                .completionsPath(config.completionsPath())
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(openAiOptions(config, config.model(), config.jsonMode()))
                .build();
        return new ProviderHandle(name, ProviderType.OPENAI, config.model(), config.maxImages(),
                config.jsonMode(), config.timeout(), model,
                (modelOverride, json) -> openAiOptions(config, or(modelOverride, config.model()), json));
    }

    private OpenAiChatOptions openAiOptions(AiProperties.Provider config, String model, boolean jsonMode) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(model)
                .temperature(config.temperature());
        if (config.maxTokens() != null) {
            builder.maxTokens(config.maxTokens());
        }
        if (jsonMode) {
            builder.responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null));
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ Anthropic

    private ProviderHandle anthropic(String name, AiProperties.Provider config) {
        AnthropicApi.Builder apiBuilder = AnthropicApi.builder()
                .apiKey(require(config.apiKey(), name, "api-key"));
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            apiBuilder.baseUrl(config.baseUrl());
        }
        AnthropicChatModel model = AnthropicChatModel.builder()
                .anthropicApi(apiBuilder.build())
                .defaultOptions(anthropicOptions(config, config.model()))
                .build();
        // Anthropic has no "response_format: json" switch; the pipeline relies on prompt
        // instructions plus JsonResponseExtractor for this provider.
        return new ProviderHandle(name, ProviderType.ANTHROPIC, config.model(), config.maxImages(),
                false, config.timeout(), model,
                (modelOverride, json) -> anthropicOptions(config, or(modelOverride, config.model())));
    }

    private AnthropicChatOptions anthropicOptions(AiProperties.Provider config, String model) {
        return AnthropicChatOptions.builder()
                .model(model)
                .temperature(config.temperature())
                .maxTokens(config.maxTokens() == null ? 8192 : config.maxTokens())
                .build();
    }

    // ------------------------------------------------------------------ optional cloud vendors

    /** Loaded only when the Azure SDK is on the classpath. */
    private static final class Azure {
        static ProviderHandle build(String name, AiProperties.Provider config) {
            com.azure.ai.openai.OpenAIClientBuilder clientBuilder = new com.azure.ai.openai.OpenAIClientBuilder()
                    .endpoint(require(config.endpoint(), name, "endpoint"))
                    .credential(new com.azure.core.credential.AzureKeyCredential(
                            require(config.apiKey(), name, "api-key")));
            String deployment = config.deploymentName() != null ? config.deploymentName() : config.model();
            org.springframework.ai.azure.openai.AzureOpenAiChatModel model =
                    org.springframework.ai.azure.openai.AzureOpenAiChatModel.builder()
                            .openAIClientBuilder(clientBuilder)
                            .defaultOptions(options(config, deployment, config.jsonMode()))
                            .build();
            return new ProviderHandle(name, ProviderType.AZURE_OPENAI, deployment, config.maxImages(),
                    config.jsonMode(), config.timeout(), model,
                    (modelOverride, json) -> options(config, or(modelOverride, deployment), json));
        }

        static org.springframework.ai.azure.openai.AzureOpenAiChatOptions options(
                AiProperties.Provider config, String deployment, boolean jsonMode) {
            org.springframework.ai.azure.openai.AzureOpenAiChatOptions.Builder builder =
                    org.springframework.ai.azure.openai.AzureOpenAiChatOptions.builder()
                            .deploymentName(deployment)
                            .temperature(config.temperature());
            if (config.maxTokens() != null) {
                builder.maxTokens(config.maxTokens());
            }
            if (jsonMode) {
                builder.responseFormat(org.springframework.ai.azure.openai.AzureOpenAiResponseFormat.builder()
                        .type(org.springframework.ai.azure.openai.AzureOpenAiResponseFormat.Type.JSON_OBJECT)
                        .build());
            }
            return builder.build();
        }
    }

    /** Loaded only when the Google Vertex SDK is on the classpath. */
    private static final class Vertex {
        static ProviderHandle build(String name, AiProperties.Provider config) {
            // Credentials come from Application Default Credentials (GOOGLE_APPLICATION_CREDENTIALS).
            com.google.cloud.vertexai.VertexAI vertexAi = new com.google.cloud.vertexai.VertexAI(
                    require(config.projectId(), name, "project-id"),
                    require(config.location(), name, "location"));
            org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel model =
                    org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel.builder()
                            .vertexAI(vertexAi)
                            .defaultOptions(options(config, config.model(), config.jsonMode()))
                            .build();
            return new ProviderHandle(name, ProviderType.VERTEX_GEMINI, config.model(), config.maxImages(),
                    config.jsonMode(), config.timeout(), model,
                    (modelOverride, json) -> options(config, or(modelOverride, config.model()), json));
        }

        static org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions options(
                AiProperties.Provider config, String model, boolean jsonMode) {
            org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions.Builder builder =
                    org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions.builder()
                            .model(model)
                            .temperature(config.temperature());
            if (config.maxTokens() != null) {
                builder.maxOutputTokens(config.maxTokens());
            }
            if (jsonMode) {
                builder.responseMimeType("application/json");
            }
            return builder.build();
        }
    }

    /** Loaded only when the AWS SDK is on the classpath. */
    private static final class Bedrock {
        static ProviderHandle build(String name, AiProperties.Provider config) {
            // Credentials come from the default AWS provider chain (env, profile, instance role).
            org.springframework.ai.bedrock.converse.BedrockProxyChatModel model =
                    org.springframework.ai.bedrock.converse.BedrockProxyChatModel.builder()
                            .region(software.amazon.awssdk.regions.Region.of(require(config.region(), name, "region")))
                            .credentialsProvider(
                                    software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.builder().build())
                            .timeout(config.timeout())
                            .defaultOptions(options(config, config.model()))
                            .build();
            // Bedrock Converse has no JSON response-format switch.
            return new ProviderHandle(name, ProviderType.BEDROCK, config.model(), config.maxImages(),
                    false, config.timeout(), model,
                    (modelOverride, json) -> options(config, or(modelOverride, config.model())));
        }

        static org.springframework.ai.bedrock.converse.BedrockChatOptions options(
                AiProperties.Provider config, String model) {
            org.springframework.ai.bedrock.converse.BedrockChatOptions.Builder builder =
                    org.springframework.ai.bedrock.converse.BedrockChatOptions.builder()
                            .model(model)
                            .temperature(config.temperature());
            if (config.maxTokens() != null) {
                builder.maxTokens(config.maxTokens());
            }
            return builder.build();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String require(String value, String providerName, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "ai.providers." + providerName + "." + property + " is required when the provider is enabled");
        }
        return value;
    }

    private static String or(String override, String fallback) {
        return override == null || override.isBlank() ? fallback : override;
    }
}
