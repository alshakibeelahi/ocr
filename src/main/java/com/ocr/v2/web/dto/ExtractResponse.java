package com.ocr.v2.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.ocr.v2.pipeline.ExtractionResult;
import com.ocr.v2.pipeline.VerificationReport;

import java.util.List;

/**
 * The v2 extraction envelope.
 *
 * <p>{@code extraction} is byte-for-byte the shape v1 returns, so migrating a consumer is a URL
 * change. Everything else is new context: whether the numbers could be verified against the
 * document, which model produced them, and which rules it was given.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtractResponse(
        String requestId,
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
        List<KnowledgeUsedDto> knowledgeUsed,
        UsageDto usage
) {

    public static ExtractResponse from(ExtractionResult result) {
        return new ExtractResponse(
                result.requestId().toString(),
                result.fileName(),
                result.provider(),
                result.model(),
                result.pageCount(),
                result.durationMs(),
                result.cached(),
                result.textLayerUsed(),
                result.signatureSource(),
                result.extraction(),
                result.verification(),
                result.knowledgeUsed().stream().map(KnowledgeUsedDto::from).toList(),
                new UsageDto(result.usage().promptTokens(), result.usage().completionTokens()));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KnowledgeUsedDto(String id, String type, String title, boolean mandatory, Double score) {

        static KnowledgeUsedDto from(ExtractionResult.KnowledgeUsed used) {
            return new KnowledgeUsedDto(used.id(), used.type().name(), used.title(), used.mandatory(), used.score());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UsageDto(Integer promptTokens, Integer completionTokens) {
    }
}
