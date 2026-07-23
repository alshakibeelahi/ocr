package com.ocr.application.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ocr.application.dto.OcrJobResponse;
import com.ocr.application.port.JobCallbackPort;
import com.ocr.application.dto.StreamEvent;
import com.ocr.application.port.OllamaVisionPort;
import com.ocr.application.port.StreamEventPublisher;
import com.ocr.domain.model.JobId;
import com.ocr.domain.model.OcrJob;
import com.ocr.domain.model.OcrPage;
import com.ocr.domain.model.PageMetadata;
import com.ocr.domain.repository.OcrJobRepository;
import com.ocr.domain.service.ImagePreprocessor;
import com.ocr.domain.service.PdfPageExtractor;
import com.ocr.interfaces.config.OcrProperties;
import com.ocr.interfaces.config.OllamaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StartPiDataExtractionJobHandler {

    private static final Logger log = LoggerFactory.getLogger(StartPiDataExtractionJobHandler.class);
    // Accepts partial codes the model reads from scans, e.g. 4819.1, 4819.10, 4819.10.00.
    private static final Pattern HS_CODE_PATTERN = Pattern.compile("\\b\\d{4}(?:\\.\\d{1,2}){1,2}\\b");
    private static final Pattern LABELLED_HS_CODE_PATTERN =
            Pattern.compile("(?i)\\bH\\.?S\\.?\\s*CODE\\s*[:\\-]?\\s*(\\d{4}(?:\\.\\d{1,4}){0,2})");
    private static final Pattern DRAFT_DAYS_PATTERN = Pattern.compile("(?i)\\b(?:at\\s*)?(\\d{1,3})\\s*days?\\s+sight\\b");
    private static final Pattern USD_HINT_PATTERN = Pattern.compile("(?i)\\bU\\.?S\\.?\\s*(?:\\$|DOLLAR)|\\bUSD\\b|\\bUS\\$");
    private static final Pattern EUR_HINT_PATTERN = Pattern.compile("(?i)\\bEURO?\\b|€");
    private static final Pattern GBP_HINT_PATTERN = Pattern.compile("(?i)\\bGBP\\b|\\bPOUND\\s+STERLING\\b|£");

    private final OcrJobRepository repository;
    private final PdfPageExtractor pdfPageExtractor;
    private final ImagePreprocessor imagePreprocessor;
    private final OllamaVisionPort ollamaVisionPort;
    private final StreamEventPublisher eventPublisher;
    private final JobCallbackPort jobCallbackPort;
    private final OcrProperties ocrProperties;
    private final OllamaProperties ollamaProperties;
    private final ObjectMapper objectMapper;
    private final Executor ocrTaskExecutor;

    public StartPiDataExtractionJobHandler(
            OcrJobRepository repository,
            PdfPageExtractor pdfPageExtractor,
            ImagePreprocessor imagePreprocessor,
            OllamaVisionPort ollamaVisionPort,
            StreamEventPublisher eventPublisher,
            JobCallbackPort jobCallbackPort,
            OcrProperties ocrProperties,
            OllamaProperties ollamaProperties,
            ObjectMapper objectMapper,
            @Qualifier("ocrTaskExecutor") Executor ocrTaskExecutor
    ) {
        this.repository = repository;
        this.pdfPageExtractor = pdfPageExtractor;
        this.imagePreprocessor = imagePreprocessor;
        this.ollamaVisionPort = ollamaVisionPort;
        this.eventPublisher = eventPublisher;
        this.jobCallbackPort = jobCallbackPort;
        this.ocrProperties = ocrProperties;
        this.ollamaProperties = ollamaProperties;
        this.objectMapper = objectMapper;
        this.ocrTaskExecutor = ocrTaskExecutor;
    }

    public JobId handle(StartPiDataExtractionJobCommand command) throws IOException {
        JobId jobId = JobId.generate();
        OcrJob job = new OcrJob(jobId, command.fileName());
        boolean pdf = isPdf(command);
        int totalPages = pdf ? pdfPageExtractor.countPages(command.pdfBytes()) : 1;
        job.initializePages(1);
        repository.save(job);
        log.info(
                "Queued PI extraction job: jobId={}, fileName={}, inputType={}, sourcePages={}, callbackUrl={}",
                jobId,
                command.fileName(),
                pdf ? "PDF" : "IMAGE",
                totalPages,
                command.callbackUrl()
        );

        ocrTaskExecutor.execute(() -> processJob(command, jobId, totalPages));

        return jobId;
    }

    private void processJob(StartPiDataExtractionJobCommand command, JobId jobId, int sourcePageCount) {
        OcrJob job = repository.findById(jobId).orElseThrow();
        job.startProcessing();
        repository.save(job);
        log.info("Started PI extraction job processing: jobId={}, sourcePages={}", jobId, sourcePageCount);

        eventPublisher.publish(StreamEvent.jobStarted(
                jobId.toString(),
                job.getFileName(),
                sourcePageCount
        ));

        try {
            processDocument(job, command, sourcePageCount);
            job.complete();
            repository.save(job);
            OcrJobResponse jobResponse = OcrJobResponse.from(job);
            log.info("PI extraction job completed successfully: jobId={}, status={}", jobId, jobResponse.status());
            eventPublisher.publish(StreamEvent.jobCompleted(
                    jobId.toString(),
                    jobResponse
            ));
            sendCallback(command.callbackUrl(), jobResponse);
        } catch (Exception e) {
            job.fail(e.getMessage());
            repository.save(job);
            log.error("PI extraction job failed: jobId={}, error={}", jobId, e.getMessage(), e);
            eventPublisher.publish(StreamEvent.error(jobId.toString(), e.getMessage(), null));
            sendCallback(command.callbackUrl(), OcrJobResponse.from(job));
        }
    }

    private void sendCallback(String callbackUrl, OcrJobResponse jobResponse) {
        try {
            log.info(
                    "Sending PI extraction callback: jobId={}, status={}, callbackUrl={}",
                    jobResponse.jobId(),
                    jobResponse.status(),
                    callbackUrl
            );
            jobCallbackPort.postJobResult(callbackUrl, jobResponse);
            log.info("PI extraction callback finished: jobId={}, callbackUrl={}", jobResponse.jobId(), callbackUrl);
        } catch (Exception callbackException) {
            // Callback delivery must not change the OCR job result after processing finishes.
            log.error("Failed to deliver callback for OCR job {} to {}", jobResponse.jobId(), callbackUrl, callbackException);
        }
    }

    private void processDocument(OcrJob job, StartPiDataExtractionJobCommand command, int sourcePageCount) throws IOException {
        JobId jobId = job.getJobId();
        OcrPage page = job.getPages().get(0);
        int resultNum = page.getPageNumber().value();
        boolean pdf = isPdf(command);

        page.startProcessing();
        repository.save(job);
        eventPublisher.publish(StreamEvent.pageStarted(jobId.toString(), resultNum, 1));
        log.info(
                "Preparing PI extraction document payload: jobId={}, page={}, inputType={}, sourcePages={}",
                jobId,
                resultNum,
                pdf ? "PDF" : "IMAGE",
                sourcePageCount
        );

        long startMs = System.currentTimeMillis();
        try {
            List<String> base64Pages = new ArrayList<>();
            int maxWidth = 0;
            int maxHeight = 0;

            if (pdf) {
                for (int pageIndex = 0; pageIndex < sourcePageCount; pageIndex++) {
                    BufferedImage rendered = pdfPageExtractor.renderPage(
                            command.pdfBytes(),
                            pageIndex,
                            ocrProperties.renderDpi()
                    );
                    ImagePreprocessor.PreprocessResult preprocessed = imagePreprocessor.preprocess(
                            rendered,
                            ocrProperties.renderDpi(),
                            ocrProperties.maxImageDimension()
                    );
                    base64Pages.add(toBase64Png(preprocessed.image()));
                    maxWidth = Math.max(maxWidth, preprocessed.metadata().width());
                    maxHeight = Math.max(maxHeight, preprocessed.metadata().height());
                }
                log.info("Prepared PDF pages for PI extraction: jobId={}, preparedImages={}", jobId, base64Pages.size());
            } else {
                ImagePreprocessor.PreprocessResult preprocessed = preprocessUploadedImage(command.pdfBytes());
                base64Pages.add(toBase64Png(preprocessed.image()));
                maxWidth = preprocessed.metadata().width();
                maxHeight = preprocessed.metadata().height();
                log.info("Prepared image upload for PI extraction: jobId={}, width={}, height={}", jobId, maxWidth, maxHeight);
            }

            log.info("Invoking vision model for PI extraction: jobId={}, imageCount={}", jobId, base64Pages.size());
            // Job-based flow: accumulate the streamed tokens in memory; clients poll the job
            // status instead of consuming per-token events.
            ollamaVisionPort.streamVision(base64Pages, promptForDocument(sourcePageCount), true, page::appendText);
            log.info("Vision model stream finished for PI extraction: jobId={}", jobId);

            long durationMs = System.currentTimeMillis() - startMs;
            page.replaceText(normalizeExtraction(page.getExtractedText()));
            PageMetadata metadata = new PageMetadata(
                    ocrProperties.renderDpi(),
                    true,
                    maxWidth,
                    maxHeight,
                    durationMs
            );
            page.complete(metadata);
            repository.save(job);
            Map<String, Object> eventMetadata = metadata.toMap();
            eventMetadata.put("sourcePageCount", sourcePageCount);
            eventMetadata.put("analyzedAs", "single-document");
            log.info(
                    "PI extraction page completed: jobId={}, page={}, durationMs={}, width={}, height={}",
                    jobId,
                    resultNum,
                    durationMs,
                    maxWidth,
                    maxHeight
            );
            eventPublisher.publish(StreamEvent.pageCompleted(
                    jobId.toString(),
                    resultNum,
                    page.getExtractedText(),
                    eventMetadata
            ));
        } catch (Exception e) {
            page.fail(e.getMessage());
            repository.save(job);
            eventPublisher.publish(StreamEvent.error(jobId.toString(), e.getMessage(), resultNum));
            throw e;
        }
    }

    private String promptForDocument(int sourcePageCount) {
        return ollamaProperties.piDataExtractionPrompt()
                + "\nThe attached images are the full document in order. Page count: " + sourcePageCount + ".";
    }

    private boolean isPdf(StartPiDataExtractionJobCommand command) {
        String contentType = command.contentType();
        if (contentType != null && contentType.equalsIgnoreCase("application/pdf")) {
            return true;
        }
        String fileName = command.fileName();
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    private ImagePreprocessor.PreprocessResult preprocessUploadedImage(byte[] fileBytes) throws IOException {
        BufferedImage sourceImage = ImageIO.read(new ByteArrayInputStream(fileBytes));
        if (sourceImage == null) {
            throw new IllegalArgumentException("Invalid image file");
        }
        return imagePreprocessor.preprocess(
                sourceImage,
                ocrProperties.renderDpi(),
                ocrProperties.maxImageDimension()
        );
    }

    private String normalizeExtraction(String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            if (!(root instanceof ObjectNode rootObject)) {
                return text;
            }

            if (rootObject.has("PROFORMA_INVOICES")) {
                normalizeMultipleInvoices(rootObject.withArray("PROFORMA_INVOICES"));
            } else {
                normalizeInvoice(rootObject);
            }

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootObject);
        } catch (Exception ignored) {
            return text;
        }
    }

    private void normalizeMultipleInvoices(ArrayNode invoices) {
        for (JsonNode invoiceNode : invoices) {
            if (invoiceNode instanceof ObjectNode invoice) {
                normalizeInvoice(invoice);
            }
        }
    }

    private void normalizeInvoice(ObjectNode invoice) {
        ObjectNode header = objectNode(invoice, "DEFN_PROFORMA_INVOICE");
        ArrayNode items = arrayNode(invoice, "DEFN_PROFORMA_INVOICE_HSC");
        String commonHsCode = findCommonHsCode(header);
        if (commonHsCode == null) {
            // Last resort: scan text labelled "HS CODE"/"H.S CODE" anywhere in the invoice.
            commonHsCode = findLabelledHsCode(invoice.toString());
        }
        JsonNode removedTotal = removeSummaryRowsAndNormalizeItems(items, commonHsCode);

        if (!header.has("total_amount") || header.get("total_amount").isNull()) {
            JsonNode totalAmount = removedTotal != null ? removedTotal : sumItemAmounts(items);
            if (totalAmount != null) {
                header.set("total_amount", totalAmount);
            }
        } else {
            normalizeAmountField(header, "total_amount");
        }

        normalizeDraftAtDays(header);
        normalizePartialShipment(header);
        normalizeCurrency(header, invoice);
    }

    private void normalizeCurrency(ObjectNode header, ObjectNode invoice) {
        JsonNode currency = header.get("currency");
        if (currency != null && !currency.isNull() && !currency.asText().isBlank()) {
            return;
        }
        // Infer currency from any text in the invoice (payment terms, remarks, content, ...).
        String haystack = invoice.toString();
        String inferred = null;
        if (USD_HINT_PATTERN.matcher(haystack).find()) {
            inferred = "USD";
        } else if (EUR_HINT_PATTERN.matcher(haystack).find()) {
            inferred = "EUR";
        } else if (GBP_HINT_PATTERN.matcher(haystack).find()) {
            inferred = "GBP";
        }
        if (inferred != null) {
            header.put("currency", inferred);
            // Traceability: record that the currency was inferred from document text,
            // not read from a dedicated currency field.
            JsonNode content = header.get("content");
            if (content instanceof ObjectNode contentObject) {
                contentObject.put("CURRENCY_SOURCE", "inferred-from-text");
            }
        }
    }

    private ObjectNode objectNode(ObjectNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        ObjectNode objectNode = objectMapper.createObjectNode();
        parent.set(fieldName, objectNode);
        return objectNode;
    }

    private ArrayNode arrayNode(ObjectNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        if (node instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        ArrayNode arrayNode = objectMapper.createArrayNode();
        parent.set(fieldName, arrayNode);
        return arrayNode;
    }

    private JsonNode removeSummaryRowsAndNormalizeItems(ArrayNode items, String commonHsCode) {
        ArrayNode kept = objectMapper.createArrayNode();
        JsonNode removedTotal = null;
        int slNo = 1;

        for (JsonNode itemNode : items) {
            if (!(itemNode instanceof ObjectNode item)) {
                continue;
            }

            normalizeAmountField(item, "unit_price");
            normalizeAmountField(item, "total_amount");

            if (isSummaryRow(item)) {
                if (item.has("total_amount") && item.get("total_amount").isNumber()) {
                    removedTotal = item.get("total_amount");
                }
                continue;
            }

            if ((!item.has("hs_code") || item.get("hs_code").isNull() || item.get("hs_code").asText().isBlank())
                    && commonHsCode != null) {
                item.put("hs_code", commonHsCode);
            }
            item.put("sl_no", slNo++);
            kept.add(item);
        }

        items.removeAll();
        items.addAll(kept);
        return removedTotal;
    }

    private boolean isSummaryRow(ObjectNode item) {
        String styleNo = text(item, "style_no");
        String description = text(item, "description");
        String hsCode = text(item, "hs_code");
        String label = (styleNo + " " + description).toLowerCase();
        boolean hasNoProductIdentity = styleNo.isBlank() && description.isBlank() && hsCode.isBlank();
        return label.contains("total") || (hasNoProductIdentity && item.has("total_amount"));
    }

    private String findCommonHsCode(ObjectNode header) {
        // Dedicated fields first (exact values, no false positives from amounts).
        String fromField = hsCodeFromFields(header);
        if (fromField != null) {
            return fromField;
        }
        JsonNode content = header.get("content");
        if (content instanceof ObjectNode contentObject) {
            fromField = hsCodeFromFields(contentObject);
            if (fromField != null) {
                return fromField;
            }
        }
        return findLabelledHsCode(header.toString());
    }

    private String hsCodeFromFields(ObjectNode node) {
        for (String fieldName : List.of("hs_code", "HS_CODE", "hsCode", "hscode", "HSCODE")) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                String text = value.asText("").trim();
                Matcher matcher = HS_CODE_PATTERN.matcher(text);
                if (matcher.find()) {
                    return matcher.group();
                }
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    /** Finds an HS code only when it appears right after an "HS CODE"-style label, so plain
     *  amounts like 5945.44 elsewhere in the JSON can never be mistaken for one. */
    private String findLabelledHsCode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = LABELLED_HS_CODE_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private void normalizeAmountField(ObjectNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || value.isNumber()) {
            return;
        }
        String raw = value.asText();
        if (raw == null || raw.isBlank()) {
            node.putNull(fieldName);
            return;
        }
        String cleaned = raw.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank() || ".".equals(cleaned) || "-".equals(cleaned)) {
            return;
        }
        try {
            node.set(fieldName, DecimalNode.valueOf(new java.math.BigDecimal(cleaned)));
        } catch (NumberFormatException ignored) {
            // Keep the original value if it is not a clean amount.
        }
    }

    private JsonNode sumItemAmounts(ArrayNode items) {
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        boolean found = false;
        for (JsonNode item : items) {
            JsonNode amount = item.get("total_amount");
            if (amount != null && amount.isNumber()) {
                total = total.add(amount.decimalValue());
                found = true;
            }
        }
        return found ? DecimalNode.valueOf(total) : null;
    }

    private void normalizeDraftAtDays(ObjectNode header) {
        JsonNode draftAtDays = header.get("draft_at_days");
        if (draftAtDays != null && !draftAtDays.isNull() && !draftAtDays.asText().isBlank()) {
            return;
        }
        String paymentTerms = text(header, "payment_terms");
        Matcher matcher = DRAFT_DAYS_PATTERN.matcher(paymentTerms);
        if (matcher.find()) {
            header.put("draft_at_days", matcher.group(1));
        }
    }

    private void normalizePartialShipment(ObjectNode header) {
        JsonNode partialShipment = header.get("partial_shipment");
        if (partialShipment != null && !partialShipment.isNull() && !partialShipment.asText().isBlank()) {
            return;
        }
        String shipmentValidity = text(header, "shipment_validity").toLowerCase();
        if (shipmentValidity.contains("partial") && (shipmentValidity.contains("allow")
                || shipmentValidity.contains("permitted"))) {
            header.put("partial_shipment", "Allowed");
        }
    }

    private String text(ObjectNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String toBase64Png(BufferedImage image) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }
}
