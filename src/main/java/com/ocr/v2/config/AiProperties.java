package com.ocr.v2.config;

import com.ocr.v2.provider.ProviderType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for the v2 Spring AI extraction stack ({@code ai.*}).
 *
 * <p>Chat providers and the embedding model are configured independently on purpose: switching the
 * chat vendor must never change the vector space the knowledge base was indexed in.
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(

        /** Provider used when a request does not name one. */
        @DefaultValue("ollama") String defaultProvider,

        /**
         * Bumped whenever the system prompt or target schema changes. Part of the cache key, so a
         * prompt change can never serve a stale cached extraction.
         */
        @DefaultValue("v2.1") String promptVersion,

        /** Chat providers, keyed by the name callers pass as {@code provider}. */
        Map<String, Provider> providers,

        @DefaultValue Embedding embedding,
        @DefaultValue Rag rag,
        @DefaultValue Extraction extraction
) {

    public AiProperties {
        providers = providers == null ? new LinkedHashMap<>() : providers;
    }

    /**
     * One chat provider. {@code type} selects the client implementation, so several named providers
     * can share one type - {@code groq}, {@code deepseek} and {@code mistral} are all
     * {@code type: OPENAI} entries with a different {@code base-url}.
     */
    public record Provider(
            @DefaultValue("false") boolean enabled,
            ProviderType type,
            String baseUrl,
            String apiKey,
            String model,
            @DefaultValue("0.0") Double temperature,
            Integer maxTokens,
            /** Hard cap for one extraction call. Deliberately generous - see the no-timeout contract. */
            @DefaultValue("30m") Duration timeout,

            // --- Ollama ---
            @DefaultValue("8192") Integer numCtx,
            @DefaultValue("30m") String keepAlive,

            // --- OpenAI-compatible ---
            @DefaultValue("/v1/chat/completions") String completionsPath,

            // --- Azure OpenAI ---
            String endpoint,
            String deploymentName,

            // --- Vertex AI Gemini ---
            String projectId,
            String location,

            // --- Bedrock ---
            String region,

            /**
             * Whether the provider supports a native "respond with JSON" mode. When false the
             * pipeline relies on prompt instructions plus {@code JsonResponseExtractor}.
             */
            @DefaultValue("true") boolean jsonMode,

            /** Guard against sending more page images than the vendor accepts. */
            @DefaultValue("40") int maxImages
    ) {
    }

    /** Embedding model for the knowledge base. Changing this requires a reindex. */
    public record Embedding(
            @DefaultValue("ollama") String provider,
            @DefaultValue("nomic-embed-text") String model,
            @DefaultValue("http://localhost:11434") String baseUrl,
            String apiKey,
            /** Must match the pgvector column dimension in the Flyway migration. */
            @DefaultValue("768") int dimensions
    ) {
    }

    /** Retrieval tuning. Retrieval is additive: it can only add hints, never remove rules. */
    public record Rag(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("8") int fieldRuleTopK,
            @DefaultValue("0.60") double fieldRuleThreshold,
            @DefaultValue("3") int layoutPatternTopK,
            @DefaultValue("0.75") double layoutPatternThreshold,
            @DefaultValue("2") int exampleTopK,
            @DefaultValue("0.80") double exampleThreshold,
            /**
             * Character budget for the injected knowledge block. Lowest-scoring non-mandatory
             * chunks are dropped first so the prompt cannot grow without bound.
             */
            @DefaultValue("14000") int maxKnowledgeChars,
            /** Re-seed built-in knowledge files on startup (upsert by checksum). */
            @DefaultValue("true") boolean seedOnStartup
    ) {
    }

    /** Pipeline behaviour. */
    public record Extraction(
            @DefaultValue("true") boolean cacheEnabled,
            /** Feed the PDF's own text layer to the model when coverage is good. */
            @DefaultValue("true") boolean includeTextLayer,
            @DefaultValue("60000") int maxTextLayerChars,
            /** Minimum extracted characters per page for the text layer to count as usable. */
            @DefaultValue("120") int minTextLayerCharsPerPage,
            @DefaultValue("2") int renderParallelism,
            /** SSE keep-alive interval; keeps proxies from closing a long extraction. */
            @DefaultValue("15s") Duration heartbeatInterval
    ) {
    }
}
