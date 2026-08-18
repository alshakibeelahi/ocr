package com.ocr.v2.knowledge;

/**
 * What a knowledge entry teaches the extractor.
 *
 * <p>None of these ever carry values for a document being processed. They describe how to read a
 * document, never what it says.
 */
public enum KnowledgeType {

    /**
     * A business rule for one or more fields: how to interpret payment terms, when an HS code
     * propagates to item rows, what counts as a summary row. The bulk of the knowledge base.
     */
    FIELD_RULE,

    /**
     * Where things sit in a particular template or issuer's document: "this supplier prints the HS
     * code in the terms block, not in the item table". Retrieved when the incoming document's
     * signature resembles the one this pattern was written for.
     */
    LAYOUT_PATTERN,

    /**
     * A redacted worked example: the shape of an input and the shape of the correct output, with
     * every digit masked. Examples teach structure. They must never contain a real value, because
     * an example value is exactly the thing a model might copy.
     */
    EXAMPLE,

    /** The target JSON schema and field dictionary. Always injected, never similarity-matched. */
    SCHEMA
}
