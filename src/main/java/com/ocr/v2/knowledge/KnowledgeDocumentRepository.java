package com.ocr.v2.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, UUID> {

    Optional<KnowledgeDocumentEntity> findBySourceKey(String sourceKey);

    List<KnowledgeDocumentEntity> findByEnabledTrue();

    List<KnowledgeDocumentEntity> findByTypeAndEnabledTrue(KnowledgeType type);

    List<KnowledgeDocumentEntity> findByAlwaysIncludeTrueAndEnabledTrue();

    long countByEnabledTrue();

    /**
     * Aggregate revision of the enabled knowledge base. Part of the extraction cache key: editing
     * any rule invalidates cached results that were produced under the old rules.
     */
    @Query("select coalesce(sum(k.revision), 0) from KnowledgeDocumentEntity k where k.enabled = true")
    long knowledgeVersion();
}
