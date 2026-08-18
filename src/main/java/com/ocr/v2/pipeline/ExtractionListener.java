package com.ocr.v2.pipeline;

/**
 * Progress callbacks from the pipeline.
 *
 * <p>The streaming endpoint turns these into SSE events, which is what makes a multi-minute
 * extraction behave like a chat rather than a request that appears to hang. The blocking endpoint
 * passes {@link #NOOP}.
 */
public interface ExtractionListener {

    ExtractionListener NOOP = new ExtractionListener() {
    };

    /** A pipeline stage started: {@code ingest}, {@code signature}, {@code retrieve}, ... */
    default void onStage(String stage, String message) {
    }

    /** A token or fragment arrived from the model. */
    default void onDelta(String text) {
    }
}
