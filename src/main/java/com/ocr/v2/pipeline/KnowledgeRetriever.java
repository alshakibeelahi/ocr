package com.ocr.v2.pipeline;

import com.ocr.v2.config.AiProperties;
import com.ocr.v2.knowledge.KnowledgeDocumentEntity;
import com.ocr.v2.knowledge.KnowledgeIndexer;
import com.ocr.v2.knowledge.KnowledgeService;
import com.ocr.v2.knowledge.KnowledgeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import com.ocr.v2.config.V2Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Selects the knowledge to inject for one document.
 *
 * <p>Two sources, in this order:
 *
 * <ol>
 *   <li><b>Mandatory</b> - the schema plus every rule marked {@code always_include}. These are not
 *       searched for, they are simply always present. A correctness rule that only applies when a
 *       similarity score happens to clear a threshold is a rule that silently stops applying, which
 *       is not acceptable for financial extraction.
 *   <li><b>Matched</b> - typed similarity searches against pgvector using the document's signature:
 *       layout patterns for this kind of template, field rules for the fields it seems to involve,
 *       redacted examples of the same shape.
 * </ol>
 *
 * <p>So retrieval is strictly <em>additive</em>. A document that resembles nothing in the knowledge
 * base still gets the full mandatory rule set - never worse than v1, only better when there is a
 * match.
 */
@V2Component
public class KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetriever.class);

    private final VectorStore vectorStore;
    private final KnowledgeService knowledgeService;
    private final AiProperties properties;

    public KnowledgeRetriever(VectorStore vectorStore, KnowledgeService knowledgeService, AiProperties properties) {
        this.vectorStore = vectorStore;
        this.knowledgeService = knowledgeService;
        this.properties = properties;
    }

    public RetrievedKnowledge retrieve(DocumentSignature signature, boolean useRag) {
        List<RetrievedKnowledge.Chunk> mandatory = mandatoryChunks();

        List<RetrievedKnowledge.Chunk> matched = List.of();
        if (useRag && properties.rag().enabled() && !signature.isEmpty()) {
            matched = matchedChunks(signature, mandatory);
        }

        return applyBudget(mandatory, matched);
    }

    // ------------------------------------------------------------------ mandatory

    private List<RetrievedKnowledge.Chunk> mandatoryChunks() {
        List<RetrievedKnowledge.Chunk> chunks = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Schema first: it defines the contract everything else refers to.
        for (KnowledgeDocumentEntity entity : knowledgeService.schemaDocuments()) {
            if (seen.add(entity.getId().toString())) {
                chunks.add(toChunk(entity, true, null));
            }
        }
        for (KnowledgeDocumentEntity entity : knowledgeService.mandatoryRules()) {
            if (seen.add(entity.getId().toString())) {
                chunks.add(toChunk(entity, true, null));
            }
        }
        return chunks;
    }

    // ------------------------------------------------------------------ similarity matched

    private List<RetrievedKnowledge.Chunk> matchedChunks(DocumentSignature signature,
                                                         List<RetrievedKnowledge.Chunk> alreadyIncluded) {
        AiProperties.Rag rag = properties.rag();
        Set<String> exclude = alreadyIncluded.stream()
                .map(RetrievedKnowledge.Chunk::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<RetrievedKnowledge.Chunk> matched = new ArrayList<>();
        matched.addAll(search(signature.text(), KnowledgeType.LAYOUT_PATTERN,
                rag.layoutPatternTopK(), rag.layoutPatternThreshold(), exclude));
        matched.addAll(search(signature.text(), KnowledgeType.FIELD_RULE,
                rag.fieldRuleTopK(), rag.fieldRuleThreshold(), exclude));
        matched.addAll(search(signature.text(), KnowledgeType.EXAMPLE,
                rag.exampleTopK(), rag.exampleThreshold(), exclude));

        matched.sort(Comparator.comparingDouble(
                (RetrievedKnowledge.Chunk chunk) -> chunk.score() == null ? 0d : chunk.score()).reversed());
        return matched;
    }

    private List<RetrievedKnowledge.Chunk> search(String query, KnowledgeType type, int topK,
                                                  double threshold, Set<String> exclude) {
        if (topK <= 0) {
            return List.of();
        }
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .filterExpression(KnowledgeIndexer.META_TYPE + " == '" + type.name() + "'")
                    .build();

            List<Document> documents = vectorStore.similaritySearch(request);
            if (documents == null) {
                return List.of();
            }

            List<RetrievedKnowledge.Chunk> chunks = new ArrayList<>();
            for (Document document : documents) {
                String id = String.valueOf(document.getMetadata()
                        .getOrDefault(KnowledgeIndexer.META_KNOWLEDGE_ID, document.getId()));
                if (!exclude.add(id)) {
                    continue;
                }
                chunks.add(new RetrievedKnowledge.Chunk(
                        id,
                        type,
                        String.valueOf(document.getMetadata().getOrDefault(KnowledgeIndexer.META_TITLE, "")),
                        document.getText() == null ? "" : document.getText(),
                        false,
                        document.getScore()));
            }
            return chunks;
        } catch (Exception e) {
            // Retrieval is an enhancement. If the vector store is unreachable the extraction still
            // runs with the mandatory rules rather than failing.
            log.warn("Knowledge retrieval for type {} failed, continuing without it: {}", type, e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ budget

    /**
     * Keeps the injected knowledge within {@code ai.rag.max-knowledge-chars}. Mandatory chunks are
     * never dropped; matched chunks are dropped lowest-score-first.
     */
    private RetrievedKnowledge applyBudget(List<RetrievedKnowledge.Chunk> mandatory,
                                           List<RetrievedKnowledge.Chunk> matched) {
        int budget = properties.rag().maxKnowledgeChars();
        List<RetrievedKnowledge.Chunk> kept = new ArrayList<>(mandatory);
        int used = mandatory.stream().mapToInt(chunk -> chunk.body().length()).sum();

        int dropped = 0;
        for (RetrievedKnowledge.Chunk chunk : matched) {
            if (used + chunk.body().length() > budget) {
                dropped++;
                continue;
            }
            kept.add(chunk);
            used += chunk.body().length();
        }

        if (dropped > 0) {
            log.info("Knowledge budget reached ({} chars): dropped {} lower-scoring chunks", budget, dropped);
        }
        return new RetrievedKnowledge(List.copyOf(kept), dropped);
    }

    private RetrievedKnowledge.Chunk toChunk(KnowledgeDocumentEntity entity, boolean mandatory, Double score) {
        return new RetrievedKnowledge.Chunk(
                entity.getId().toString(),
                entity.getType(),
                entity.getTitle(),
                entity.getBody(),
                mandatory,
                score);
    }
}
