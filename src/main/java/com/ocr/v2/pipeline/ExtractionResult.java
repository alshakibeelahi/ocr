package com.ocr.v2.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.ocr.v2.knowledge.KnowledgeType;

import java.util.List;
import java.util.UUID;

/**
 * The finished extraction.
 *
 * <p>{@code extraction} keeps exactly the v1 shape, so a caller moving from
 * {@code /api/v1/pi-data-extraction} only has to change the URL. Everything new -
 * verification, provenance, cost - sits alongside it rather than inside it.
 */
public record ExtractionResult(
        UUID requestId,
        String fileName,
        String provider,
        String model,
        int pageCount,
        long durationMs,
        boolean cached,
        boolean textLayerUsed,
        String signatureSource,
        JsonNode extraction,
        VerificationReport verification,
        List<KnowledgeUsed> knowledgeUsed,
        Usage usage
) {

    /**
     * A knowledge entry that shaped this extraction. Exposed so a reviewer questioning an output
     * can see exactly which rules the model was given.
     *
     * @param mandatory true when injected unconditionally rather than by similarity match
     */
    public record KnowledgeUsed(String id, KnowledgeType type, String title, boolean mandatory, Double score) {
    }

    public record Usage(Integer promptTokens, Integer completionTokens) {

        public static final Usage EMPTY = new Usage(null, null);
    }
}
