package com.ocr.v2.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Beans for the v2 stack.
 *
 * <p>The Spring AI model starters' auto-configuration is switched off
 * ({@code spring.ai.model.chat=none}, {@code spring.ai.model.embedding=none}) so that having six
 * vendor starters on the classpath cannot produce competing {@code ChatModel} beans or fail startup
 * over a missing API key. Everything here is constructed explicitly instead.
 */
@Configuration
@Profile("!lite")
@EnableConfigurationProperties(AiProperties.class)
public class V2AiConfig {

    private static final Logger log = LoggerFactory.getLogger(V2AiConfig.class);

    /** Table the knowledge index lives in. Created by Flyway, not by Spring AI. */
    public static final String VECTOR_TABLE = "knowledge_vector";

    /**
     * The embedding model is deliberately independent of the chat provider: the vector space the
     * knowledge base was indexed in must not change when a caller switches chat vendor. Changing
     * this model (or its dimensions) requires {@code POST /api/v2/knowledge/reindex}.
     */
    @Bean
    public EmbeddingModel knowledgeEmbeddingModel(AiProperties properties) {
        AiProperties.Embedding embedding = properties.embedding();
        log.info("Knowledge embedding model: provider={}, model={}, dimensions={}",
                embedding.provider(), embedding.model(), embedding.dimensions());

        if ("openai".equalsIgnoreCase(embedding.provider())) {
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(embedding.baseUrl())
                    .apiKey(embedding.apiKey() == null ? "" : embedding.apiKey())
                    .build();
            return new OpenAiEmbeddingModel(api,
                    org.springframework.ai.document.MetadataMode.EMBED,
                    OpenAiEmbeddingOptions.builder().model(embedding.model()).build());
        }

        OllamaApi api = OllamaApi.builder().baseUrl(embedding.baseUrl()).build();
        return OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .defaultOptions(OllamaEmbeddingOptions.builder().model(embedding.model()).build())
                .build();
    }

    /**
     * pgvector store over the Flyway-managed {@code knowledge_vector} table.
     *
     * <p>{@code initializeSchema(false)} on purpose: schema ownership stays with Flyway so the
     * embedding dimension is pinned in one reviewable place and cannot drift between environments.
     */
    @Bean
    public VectorStore knowledgeVectorStore(JdbcTemplate jdbcTemplate,
                                            EmbeddingModel embeddingModel,
                                            AiProperties properties) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName("public")
                .vectorTableName(VECTOR_TABLE)
                .dimensions(properties.embedding().dimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(false)
                .vectorTableValidationsEnabled(true)
                .build();
    }

    /**
     * Dedicated pool for v2 work (page rendering, the blocking extract endpoint), kept separate
     * from v1's {@code ocrTaskExecutor} so v2 traffic cannot starve existing OCR jobs.
     */
    @Bean(name = "aiTaskExecutor")
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-v2-");
        executor.initialize();
        return executor;
    }

    /**
     * Sends SSE keep-alive comments during long extractions. Separate from the work pool on
     * purpose: a heartbeat that queues behind extraction work is not a heartbeat.
     */
    @Bean(name = "sseHeartbeatScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService sseHeartbeatScheduler() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ai-v2-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}
