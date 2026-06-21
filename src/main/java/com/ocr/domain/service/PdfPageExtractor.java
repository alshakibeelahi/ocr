package com.ocr.domain.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

public interface PdfPageExtractor {

    int countPages(byte[] pdfBytes) throws IOException;

    BufferedImage renderPage(byte[] pdfBytes, int pageIndex, int dpi) throws IOException;

    default List<BufferedImage> renderAllPages(byte[] pdfBytes, int dpi) throws IOException {
        int pageCount = countPages(pdfBytes);
        return java.util.stream.IntStream.range(0, pageCount)
                .mapToObj(index -> {
                    try {
                        return renderPage(pdfBytes, index, dpi);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to render page " + index, e);
                    }
                })
                .toList();
    }
}
