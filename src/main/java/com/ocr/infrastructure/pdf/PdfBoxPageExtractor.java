package com.ocr.infrastructure.pdf;

import com.ocr.domain.service.PdfPageExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;

@Component
public class PdfBoxPageExtractor implements PdfPageExtractor {

    @Override
    public int countPages(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return document.getNumberOfPages();
        }
    }

    @Override
    public BufferedImage renderPage(byte[] pdfBytes, int pageIndex, int dpi) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
                throw new IllegalArgumentException("Page index out of bounds: " + pageIndex);
            }
            PDFRenderer renderer = new PDFRenderer(document);
            return renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
        }
    }
}
