package com.ocr.v2.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import com.ocr.v2.config.V2AiConfig;
import com.ocr.v2.config.V2Component;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the pgvector index in sync with {@link KnowledgeDocumentEntity} rows.
 *
 * <p>Metadata written alongside each vector is what the retriever filters on, so it must stay in
 * step with {@code KnowledgeRetriever}'s filter expressions.
 */
@V2Component
public class KnowledgeIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

    public static final String META_TYPE = "type";
    public static final String META_KNOWLEDGE_ID = "knowledgeId";
    public static final String META_TITLE = "title";
    public static final String META_FIELD = "fieldName";
    public static final String META_ISSUER = "issuer";
    public static final String META_TAGS = "tags";
    public static final String META_ALWAYS_INCLUDE = "alwaysInclude";

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeIndexer(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * How many vectors are actually in the index.
     *
     * <p>Reported by the health endpoint next to the knowledge-document count. If the two disagree,
     * retrieval is quietly returning less than it should - the failure mode this exists to make
     * visible.
     */
    public long indexedCount() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from " + V2AiConfig.VECTOR_TABLE, Long.class);
        return count == null ? 0 : count;
    }

    /** Re-embeds one entry, replacing any previous vector for it. */
    public void index(KnowledgeDocumentEntity entity) {
        remove(entity);
        if (!entity.isEnabled()) {
            return;
        }
        vectorStore.add(List.of(toDocument(entity)));
    }

    public void index(List<KnowledgeDocumentEntity> entities) {
        List<Document> documents = entities.stream()
                .filter(KnowledgeDocumentEntity::isEnabled)
                .map(this::toDocument)
                .toList();
        if (documents.isEmpty()) {
            return;
        }
        // Batched: the store splits into embedding-API-sized chunks internally.
        vectorStore.add(documents);
        log.info("Indexed {} knowledge documents", documents.size());
    }

    public void remove(KnowledgeDocumentEntity entity) {
        try {
            vectorStore.delete(List.of(entity.getId().toString()));
        } catch (Exception e) {
            // Deleting a vector that was never written is not an error worth failing a request for.
            log.debug("No existing vector to remove for knowledge {}: {}", entity.getId(), e.getMessage());
        }
    }

    /** Full rebuild - the recovery path after changing the embedding model. */
    public int reindexAll(List<KnowledgeDocumentEntity> all) {
        for (KnowledgeDocumentEntity entity : all) {
            remove(entity);
        }
        index(all);
        return (int) all.stream().filter(KnowledgeDocumentEntity::isEnabled).count();
    }

    private Document toDocument(KnowledgeDocumentEntity entity) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(META_KNOWLEDGE_ID, entity.getId().toString());
        metadata.put(META_TYPE, entity.getType().name());
        metadata.put(META_TITLE, entity.getTitle());
        metadata.put(META_ALWAYS_INCLUDE, entity.isAlwaysInclude());
        metadata.put(META_FIELD, entity.getFieldName() == null ? "" : entity.getFieldName());
        metadata.put(META_ISSUER, entity.getIssuer() == null ? "" : entity.getIssuer());
        metadata.put(META_TAGS, entity.getTags() == null ? "" : entity.getTags());

        return Document.builder()
                .id(entity.getId().toString())
                .text(entity.embeddableText())
                .metadata(metadata)
                .build();
    }
}
