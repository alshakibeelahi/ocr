package com.ocr.v2.pdf;

import com.ocr.domain.service.ImagePreprocessor;
import com.ocr.interfaces.config.OcrProperties;
import com.ocr.v2.config.AiProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ocr.v2.config.V2Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Turns an uploaded PDF or image into everything the v2 pipeline needs, in a single pass.
 *
 * <p>Unlike v1's {@code PdfBoxPageExtractor} - which reopens and reparses the whole PDF for every
 * page - this opens the document once and reads both the rendered images and the embedded text
 * layer from it. On a 10-page invoice that is one parse instead of eleven.
 *
 * <p>The text layer matters beyond speed: for digitally generated PDFs (most ERP-produced PIs) it
 * gives exact characters for amounts and references, which the pipeline uses both to ground the
 * model and to verify its output afterwards.
 */
@V2Component
public class PdfDocumentReader {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentReader.class);

    private final ImagePreprocessor imagePreprocessor;
    private final OcrProperties ocrProperties;
    private final AiProperties aiProperties;

    public PdfDocumentReader(ImagePreprocessor imagePreprocessor,
                             OcrProperties ocrProperties,
                             AiProperties aiProperties) {
        this.imagePreprocessor = imagePreprocessor;
        this.ocrProperties = ocrProperties;
        this.aiProperties = aiProperties;
    }

    public PreparedDocument read(byte[] bytes, String fileName, String contentType) throws IOException {
        return isPdf(fileName, contentType) ? readPdf(bytes) : readImage(bytes);
    }

    private PreparedDocument readPdf(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pageCount = document.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(document);

            List<String> images = new ArrayList<>(pageCount);
            List<String> pageTexts = new ArrayList<>(pageCount);
            int maxWidth = 0;
            int maxHeight = 0;

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                BufferedImage rendered = renderer.renderImageWithDPI(
                        pageIndex, ocrProperties.renderDpi(), ImageType.RGB);
                ImagePreprocessor.PreprocessResult preprocessed = imagePreprocessor.preprocess(
                        rendered, ocrProperties.renderDpi(), ocrProperties.maxImageDimension());
                images.add(toBase64Png(preprocessed.image()));
                maxWidth = Math.max(maxWidth, preprocessed.metadata().width());
                maxHeight = Math.max(maxHeight, preprocessed.metadata().height());

                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                pageTexts.add(safeText(stripper, document, pageIndex));
            }

            String textLayer = String.join("\n", pageTexts).strip();
            boolean usable = isTextLayerUsable(textLayer, pageCount);
            log.info("Prepared PDF for v2 extraction: pages={}, textLayerChars={}, textLayerUsable={}",
                    pageCount, textLayer.length(), usable);

            return new PreparedDocument(images, pageTexts, textLayer, usable, pageCount, maxWidth, maxHeight, true);
        }
    }

    private PreparedDocument readImage(byte[] bytes) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) {
            throw new IllegalArgumentException("Invalid image file");
        }
        ImagePreprocessor.PreprocessResult preprocessed = imagePreprocessor.preprocess(
                source, ocrProperties.renderDpi(), ocrProperties.maxImageDimension());
        // A raster image has no text layer, so grounding checks are unavailable for it.
        return new PreparedDocument(
                List.of(toBase64Png(preprocessed.image())),
                List.of(""),
                "",
                false,
                1,
                preprocessed.metadata().width(),
                preprocessed.metadata().height(),
                false);
    }

    private String safeText(PDFTextStripper stripper, PDDocument document, int pageIndex) {
        try {
            return stripper.getText(document).strip();
        } catch (IOException e) {
            // A damaged text layer must not fail the extraction - the images are the primary input.
            log.warn("Could not read text layer of page {}: {}", pageIndex + 1, e.getMessage());
            return "";
        }
    }

    private boolean isTextLayerUsable(String textLayer, int pageCount) {
        int minimum = aiProperties.extraction().minTextLayerCharsPerPage() * Math.max(1, pageCount);
        return textLayer.length() >= minimum;
    }

    public static boolean isPdf(String fileName, String contentType) {
        if (contentType != null && contentType.equalsIgnoreCase("application/pdf")) {
            return true;
        }
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    private static String toBase64Png(BufferedImage image) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }
}
