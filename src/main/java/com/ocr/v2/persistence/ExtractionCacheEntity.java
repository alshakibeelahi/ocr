package com.ocr.v2.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Result cache keyed by the exact bytes of the upload plus everything that could change the answer.
 *
 * <p>Note what this is NOT: it is not "this invoice looks like one I have seen, reuse that answer".
 * The key includes the SHA-256 of the file, so a hit means byte-identical input under an identical
 * provider, model, prompt version and knowledge base - the same question, not a similar one. Two
 * invoices from the same supplier with different amounts can never collide.
 */
@Entity
@Table(name = "extraction_cache")
public class ExtractionCacheEntity {

    @Id
    @Column(name = "cache_key", nullable = false, length = 128)
    private String cacheKey;

    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "model")
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(name = "knowledge_version", nullable = false)
    private long knowledgeVersion;

    /** So a cache hit can report the same page count without reparsing the PDF. */
    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", nullable = false, columnDefinition = "jsonb")
    private String result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verification", columnDefinition = "jsonb")
    private String verification;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "hits", nullable = false)
    private int hits;

    protected ExtractionCacheEntity() {
        // JPA
    }

    public ExtractionCacheEntity(String cacheKey, String fileSha256, String provider, String model,
                                 String promptVersion, long knowledgeVersion, int pageCount,
                                 String result, String verification) {
        this.cacheKey = cacheKey;
        this.fileSha256 = fileSha256;
        this.provider = provider;
        this.model = model;
        this.promptVersion = promptVersion;
        this.knowledgeVersion = knowledgeVersion;
        this.pageCount = pageCount;
        this.result = result;
        this.verification = verification;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public long getKnowledgeVersion() {
        return knowledgeVersion;
    }

    public int getPageCount() {
        return pageCount;
    }

    public String getResult() {
        return result;
    }

    public String getVerification() {
        return verification;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getHits() {
        return hits;
    }

    public void recordHit() {
        this.hits++;
    }
}
