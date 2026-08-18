package com.ocr.v2.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The source of truth for one piece of extraction knowledge.
 *
 * <p>The pgvector table is a derived index over these rows: it can be dropped and rebuilt from here
 * at any time via {@code POST /api/v2/knowledge/reindex}, which is what makes changing the
 * embedding model a recoverable operation.
 */
@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocumentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /**
     * Stable identifier for seeded entries ({@code field-rule.hs-code-propagation}). Lets a seed
     * file be edited and re-applied without creating duplicates. Null for API-created entries.
     */
    @Column(name = "source_key", unique = true)
    private String sourceKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private KnowledgeType type;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    /** Field this rule is about, when it is about exactly one (e.g. {@code draft_at_days}). */
    @Column(name = "field_name")
    private String fieldName;

    /** Issuer/template this pattern was observed on, for LAYOUT_PATTERN entries. */
    @Column(name = "issuer")
    private String issuer;

    /** Comma-separated free-form tags, used for filtering and for operators browsing the base. */
    @Column(name = "tags")
    private String tags;

    /**
     * Mandatory rules bypass similarity search entirely and are always injected. Correctness rules
     * for financial fields belong here: a rule that only fires when retrieval happens to score it
     * highly is a rule that silently stops applying.
     */
    @Column(name = "always_include", nullable = false)
    private boolean alwaysInclude;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Checksum of the seeded content, so re-seeding only touches entries that actually changed. */
    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    /** Bumped on every content change; part of the extraction cache key. */
    @Column(name = "revision", nullable = false)
    private int revision = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeDocumentEntity() {
        // JPA
    }

    public KnowledgeDocumentEntity(UUID id, KnowledgeType type, String title, String body) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.body = body;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Text that gets embedded. Title and tags are included so a signature can match on them. */
    public String embeddableText() {
        StringBuilder text = new StringBuilder(title).append('\n');
        if (issuer != null && !issuer.isBlank()) {
            text.append("Issuer/template: ").append(issuer).append('\n');
        }
        if (fieldName != null && !fieldName.isBlank()) {
            text.append("Field: ").append(fieldName).append('\n');
        }
        if (tags != null && !tags.isBlank()) {
            text.append("Tags: ").append(tags).append('\n');
        }
        return text.append(body).toString();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public KnowledgeType getType() {
        return type;
    }

    public void setType(KnowledgeType type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public boolean isAlwaysInclude() {
        return alwaysInclude;
    }

    public void setAlwaysInclude(boolean alwaysInclude) {
        this.alwaysInclude = alwaysInclude;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
