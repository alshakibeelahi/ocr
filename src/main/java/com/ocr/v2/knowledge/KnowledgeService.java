package com.ocr.v2.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** CRUD over the knowledge base, keeping the vector index in step with every write. */
@Service
@Profile("!lite")
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final KnowledgeDocumentRepository repository;
    private final KnowledgeIndexer indexer;

    public KnowledgeService(KnowledgeDocumentRepository repository, KnowledgeIndexer indexer) {
        this.repository = repository;
        this.indexer = indexer;
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentEntity> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentEntity get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new KnowledgeNotFoundException("No knowledge document with id " + id));
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentEntity> mandatoryRules() {
        return repository.findByAlwaysIncludeTrueAndEnabledTrue();
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentEntity> schemaDocuments() {
        return repository.findByTypeAndEnabledTrue(KnowledgeType.SCHEMA);
    }

    @Transactional(readOnly = true)
    public long knowledgeVersion() {
        return repository.knowledgeVersion();
    }

    @Transactional(readOnly = true)
    public long enabledCount() {
        return repository.countByEnabledTrue();
    }

    /** Vectors actually present in the index. Should track {@link #enabledCount()}. */
    @Transactional(readOnly = true)
    public long indexedCount() {
        return indexer.indexedCount();
    }

    @Transactional
    public KnowledgeDocumentEntity create(KnowledgeType type, String title, String body, String fieldName,
                                          String issuer, String tags, boolean alwaysInclude, boolean enabled) {
        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity(UUID.randomUUID(), type, title, body);
        entity.setFieldName(fieldName);
        entity.setIssuer(issuer);
        entity.setTags(tags);
        entity.setAlwaysInclude(alwaysInclude);
        entity.setEnabled(enabled);
        entity.setChecksum(checksum(title, body));
        KnowledgeDocumentEntity saved = repository.save(entity);
        indexer.index(saved);
        log.info("Created knowledge document: id={}, type={}, title='{}'", saved.getId(), type, title);
        return saved;
    }

    @Transactional
    public KnowledgeDocumentEntity update(UUID id, KnowledgeType type, String title, String body, String fieldName,
                                          String issuer, String tags, Boolean alwaysInclude, Boolean enabled) {
        KnowledgeDocumentEntity entity = get(id);
        String previousChecksum = entity.getChecksum();

        if (type != null) {
            entity.setType(type);
        }
        if (title != null) {
            entity.setTitle(title);
        }
        if (body != null) {
            entity.setBody(body);
        }
        if (fieldName != null) {
            entity.setFieldName(fieldName);
        }
        if (issuer != null) {
            entity.setIssuer(issuer);
        }
        if (tags != null) {
            entity.setTags(tags);
        }
        if (alwaysInclude != null) {
            entity.setAlwaysInclude(alwaysInclude);
        }
        if (enabled != null) {
            entity.setEnabled(enabled);
        }

        entity.setChecksum(checksum(entity.getTitle(), entity.getBody()));
        if (!entity.getChecksum().equals(previousChecksum)) {
            // Revision feeds the extraction cache key, so an edited rule invalidates stale results.
            entity.setRevision(entity.getRevision() + 1);
        }

        KnowledgeDocumentEntity saved = repository.save(entity);
        indexer.index(saved);
        log.info("Updated knowledge document: id={}, revision={}", saved.getId(), saved.getRevision());
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        KnowledgeDocumentEntity entity = get(id);
        indexer.remove(entity);
        repository.delete(entity);
        log.info("Deleted knowledge document: id={}", id);
    }

    @Transactional
    public int reindexAll() {
        int count = indexer.reindexAll(repository.findAll());
        log.info("Reindexed knowledge base: {} enabled documents embedded", count);
        return count;
    }

    static String checksum(String title, String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(title.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    /** Requested knowledge document does not exist. Maps to HTTP 404. */
    public static class KnowledgeNotFoundException extends RuntimeException {
        public KnowledgeNotFoundException(String message) {
            super(message);
        }
    }
}
