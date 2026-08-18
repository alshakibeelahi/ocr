package com.ocr.v2.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ocr.v2.knowledge.KnowledgeDocumentEntity;
import com.ocr.v2.knowledge.KnowledgeType;

import java.time.Instant;
import java.util.List;

/** A knowledge base entry as seen over the API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeDto(
        String id,
        String sourceKey,
        KnowledgeType type,
        String title,
        String body,
        String fieldName,
        String issuer,
        List<String> tags,
        boolean alwaysInclude,
        boolean enabled,
        int revision,
        Instant createdAt,
        Instant updatedAt
) {

    public static KnowledgeDto from(KnowledgeDocumentEntity entity) {
        return new KnowledgeDto(
                entity.getId().toString(),
                entity.getSourceKey(),
                entity.getType(),
                entity.getTitle(),
                entity.getBody(),
                entity.getFieldName(),
                entity.getIssuer(),
                splitTags(entity.getTags()),
                entity.isAlwaysInclude(),
                entity.isEnabled(),
                entity.getRevision(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return List.of(tags.split("\\s*,\\s*"));
    }

    /** Create/update payload. On update, null fields are left unchanged. */
    public record UpsertRequest(
            KnowledgeType type,
            String title,
            String body,
            String fieldName,
            String issuer,
            List<String> tags,
            Boolean alwaysInclude,
            Boolean enabled
    ) {

        public String joinedTags() {
            return tags == null || tags.isEmpty() ? null : String.join(",", tags);
        }
    }
}
