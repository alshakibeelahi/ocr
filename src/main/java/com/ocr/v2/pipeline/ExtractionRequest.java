package com.ocr.v2.pipeline;

/**
 * One extraction request.
 *
 * @param provider         provider name, or null for {@code ai.default-provider}
 * @param model            model id override, or null for the provider's configured model
 * @param useRag           inject retrieved knowledge; false runs a bare extraction, useful for
 *                         A/B checking what the knowledge base is actually contributing
 * @param includeTextLayer feed the PDF's own text to the model alongside the images
 * @param useCache         allow a byte-identical previous result to be returned
 */
public record ExtractionRequest(
        String fileName,
        byte[] bytes,
        String contentType,
        String provider,
        String model,
        boolean useRag,
        boolean includeTextLayer,
        boolean useCache
) {
}
