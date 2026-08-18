package com.ocr.v2.pdf;

import java.util.List;

/**
 * A document ready for extraction: page images the model will see, plus the text the document
 * itself declares.
 *
 * @param base64Images     one preprocessed PNG per page, in document order
 * @param pageTexts        embedded text per page (empty strings for scans)
 * @param textLayer        all page text joined; the authoritative spelling of what is printed
 * @param textLayerUsable  whether there is enough text for grounding checks to mean anything
 * @param pageCount        source page count
 * @param maxWidth         widest preprocessed page, for reporting
 * @param maxHeight        tallest preprocessed page, for reporting
 * @param pdf              true when the upload was a PDF rather than a raster image
 */
public record PreparedDocument(
        List<String> base64Images,
        List<String> pageTexts,
        String textLayer,
        boolean textLayerUsable,
        int pageCount,
        int maxWidth,
        int maxHeight,
        boolean pdf
) {

    public int imageCount() {
        return base64Images.size();
    }
}
