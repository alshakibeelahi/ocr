package com.ocr.v2.pipeline;

import java.util.List;

/**
 * A cheap fingerprint of what kind of document this is - the retrieval key.
 *
 * <p>Deliberately built from structural cues only (who issued it, what the table columns are
 * called, which currency tokens appear) and never from values. The signature answers "what does
 * this look like", so the knowledge base can answer "here is how to read that kind of thing".
 *
 * @param source where the signature came from, for the audit trail
 */
public record DocumentSignature(
        String text,
        List<String> issuerCandidates,
        List<String> columnHeaders,
        List<String> currencyTokens,
        List<String> documentTypeHints,
        Source source
) {

    public enum Source {
        /** Derived from the PDF's embedded text layer - free and exact. */
        TEXT_LAYER,
        /** Derived from one small vision call on page 1, used for scans. */
        VISION,
        /** No signature could be built; only mandatory knowledge will be injected. */
        NONE
    }

    public static DocumentSignature none() {
        return new DocumentSignature("", List.of(), List.of(), List.of(), List.of(), Source.NONE);
    }

    public boolean isEmpty() {
        return text == null || text.isBlank();
    }
}
