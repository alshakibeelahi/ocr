package com.ocr.v2.pipeline;

import com.ocr.v2.knowledge.KnowledgeType;

import java.util.List;

/**
 * The knowledge selected for one extraction, in the order it will be injected.
 *
 * @param chunks   what actually goes into the prompt
 * @param droppedForBudget how many similarity-matched chunks were cut to stay within the character
 *                         budget; reported so a shrinking prompt is visible rather than silent
 */
public record RetrievedKnowledge(List<Chunk> chunks, int droppedForBudget) {

    public static RetrievedKnowledge empty() {
        return new RetrievedKnowledge(List.of(), 0);
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public List<String> ids() {
        return chunks.stream().map(Chunk::id).toList();
    }

    /**
     * @param mandatory true when the chunk was injected because it is marked always-include rather
     *                  than because it matched the document's signature
     * @param score     similarity score, null for mandatory chunks (they were never scored)
     */
    public record Chunk(
            String id,
            KnowledgeType type,
            String title,
            String body,
            boolean mandatory,
            Double score
    ) {
    }
}
