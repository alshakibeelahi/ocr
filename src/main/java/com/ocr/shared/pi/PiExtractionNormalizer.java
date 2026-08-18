package com.ocr.shared.pi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic post-processing of the model's PI extraction JSON.
 *
 * <p>This is the v1 normalisation logic, moved here verbatim so that both the v1 job flow
 * ({@code StartPiDataExtractionJobHandler}) and the v2 Spring AI pipeline produce byte-identical
 * output for the same model response. Behaviour must not change: v1 consumers depend on it.
 *
 * <p>Every rule here is a documented, reversible transformation of what the model returned - it
 * never invents a value that was not present in the model output.
 */
@Component
public class PiExtractionNormalizer {

    // Accepts partial codes the model reads from scans, e.g. 4819.1, 4819.10, 4819.10.00.
    private static final Pattern HS_CODE_PATTERN = Pattern.compile("\\b\\d{4}(?:\\.\\d{1,2}){1,2}\\b");
    private static final Pattern LABELLED_HS_CODE_PATTERN =
            Pattern.compile("(?i)\\bH\\.?S\\.?\\s*CODE\\s*[:\\-]?\\s*(\\d{4}(?:\\.\\d{1,4}){0,2})");
    private static final Pattern DRAFT_DAYS_PATTERN = Pattern.compile("(?i)\\b(?:at\\s*)?(\\d{1,3})\\s*days?\\s+sight\\b");
    private static final Pattern USD_HINT_PATTERN = Pattern.compile("(?i)\\bU\\.?S\\.?\\s*(?:\\$|DOLLAR)|\\bUSD\\b|\\bUS\\$");
    private static final Pattern EUR_HINT_PATTERN = Pattern.compile("(?i)\\bEURO?\\b|€");
    private static final Pattern GBP_HINT_PATTERN = Pattern.compile("(?i)\\bGBP\\b|\\bPOUND\\s+STERLING\\b|£");

    private final ObjectMapper objectMapper;

    public PiExtractionNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Normalises the raw model output. Returns the input unchanged when it is not parseable JSON,
     * so a malformed response is surfaced to the caller rather than swallowed.
     */
    public String normalize(String text) {
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

    /**
     * Same rules as {@link #normalize(String)} but operating on an already-parsed tree, for callers
     * that have the JSON in hand and do not want a serialise/parse round trip.
     */
    public void normalizeTree(ObjectNode rootObject) {
        if (rootObject.has("PROFORMA_INVOICES")) {
            normalizeMultipleInvoices(rootObject.withArray("PROFORMA_INVOICES"));
        } else {
            normalizeInvoice(rootObject);
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
}
