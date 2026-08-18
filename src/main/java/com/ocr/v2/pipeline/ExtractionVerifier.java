package com.ocr.v2.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import com.ocr.v2.config.V2Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks an extraction against the document it came from. Deterministic - no model involved.
 *
 * <p>Three independent checks:
 *
 * <ol>
 *   <li><b>Schema</b> - the output has the contracted shape and types.
 *   <li><b>Arithmetic</b> - quantity x unit price reconciles with each line total, and the line
 *       totals reconcile with the header total.
 *   <li><b>Grounding</b> - each extracted value can be found verbatim in the document's own text
 *       layer. This is what turns "the model said so" into "the document says so". Only possible
 *       for PDFs with a text layer; for scans it is reported as unavailable rather than as passed.
 * </ol>
 *
 * <p>Nothing here modifies the extraction. Every finding is reported for a human to act on.
 */
@V2Component
public class ExtractionVerifier {

    private static final Logger log = LoggerFactory.getLogger(ExtractionVerifier.class);

    private static final String SCHEMA_PATH = "schema/proforma-invoice.schema.json";

    /** Relative tolerance for reconciliation, alongside an absolute floor for rounding noise. */
    private static final BigDecimal RELATIVE_TOLERANCE = new BigDecimal("0.005");
    private static final BigDecimal ABSOLUTE_TOLERANCE = new BigDecimal("0.02");

    /**
     * Fields that are produced or rewritten by {@code PiExtractionNormalizer} rather than read off
     * the page, so a verbatim match against the document is not the right test for them.
     */
    private static final Set<String> DERIVED_HEADER_FIELDS = Set.of(
            "status",           // constant "Draft"
            "currency",         // may be inferred from symbols/words rather than printed as a code
            "draft_at_days",    // may be derived from the payment terms sentence
            "partial_shipment", // may be derived from the shipment clause
            "content",          // free-form catch-all object
            "total_amount");    // checked by arithmetic instead, see below

    private static final Set<String> DERIVED_ITEM_FIELDS = Set.of(
            "sl_no");           // renumbered after summary rows are dropped

    /** Currency evidence that legitimately appears as a symbol or word rather than an ISO code. */
    private static final Map<String, List<String>> CURRENCY_ALIASES = Map.of(
            "USD", List.of("usd", "us$", "u.s.$", "us $", "usdollar", "u.s.dollar", "dollar", "$"),
            "EUR", List.of("eur", "euro", "€"),
            "GBP", List.of("gbp", "poundsterling", "pound", "£"),
            "BDT", List.of("bdt", "taka", "৳"),
            "JPY", List.of("jpy", "yen", "¥"),
            "INR", List.of("inr", "rupee", "₹"));

    private final JsonSchema schema;

    public ExtractionVerifier(ObjectMapper objectMapper) {
        this.schema = loadSchema(objectMapper);
    }

    public VerificationReport verify(JsonNode extraction, String textLayer, boolean textLayerUsable) {
        List<VerificationReport.Check> checks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> ungrounded = new ArrayList<>();

        boolean schemaValid = checkSchema(extraction, checks, warnings);

        String normalizedText = textLayerUsable ? normalizeForSearch(textLayer) : "";
        String digitsText = textLayerUsable ? digitsOnly(textLayer) : "";

        int[] groundingCounters = new int[2]; // [checked, grounded]

        if (extraction.has("PROFORMA_INVOICES")) {
            JsonNode invoices = extraction.get("PROFORMA_INVOICES");
            for (int i = 0; i < invoices.size(); i++) {
                verifyInvoice(invoices.get(i), "PROFORMA_INVOICES[" + i + "].", checks, warnings,
                        ungrounded, normalizedText, digitsText, textLayerUsable, groundingCounters);
            }
        } else {
            verifyInvoice(extraction, "", checks, warnings, ungrounded,
                    normalizedText, digitsText, textLayerUsable, groundingCounters);
        }

        if (!textLayerUsable) {
            checks.add(VerificationReport.Check.fail("grounding",
                    "No text layer available (scanned document or image upload) - extracted values "
                            + "could not be checked against the document text"));
            warnings.add("Grounding checks were not possible: this document has no text layer. "
                    + "Values were read from the image only and are unverified.");
        } else {
            String detail = groundingCounters[1] + " of " + groundingCounters[0]
                    + " extracted values found verbatim in the document text";
            if (ungrounded.isEmpty()) {
                checks.add(VerificationReport.Check.pass("grounding", detail));
            } else {
                checks.add(VerificationReport.Check.fail("grounding", detail));
                warnings.add(ungrounded.size() + " extracted value(s) could not be found in the document text. "
                        + "Review them before use: " + String.join(", ", ungrounded.subList(0, Math.min(10, ungrounded.size())))
                        + (ungrounded.size() > 10 ? ", ..." : ""));
            }
        }

        boolean allPassed = checks.stream().allMatch(VerificationReport.Check::passed);
        return new VerificationReport(
                allPassed ? VerificationReport.Status.PASS : VerificationReport.Status.WARN,
                schemaValid,
                textLayerUsable,
                groundingCounters[0],
                groundingCounters[1],
                List.copyOf(checks),
                List.copyOf(warnings),
                List.copyOf(ungrounded));
    }

    // ------------------------------------------------------------------ schema

    private boolean checkSchema(JsonNode extraction, List<VerificationReport.Check> checks, List<String> warnings) {
        if (schema == null) {
            checks.add(VerificationReport.Check.fail("schema", "Schema could not be loaded; validation skipped"));
            return false;
        }
        Set<ValidationMessage> violations = schema.validate(extraction);
        if (violations.isEmpty()) {
            checks.add(VerificationReport.Check.pass("schema", "Output matches the target schema"));
            return true;
        }
        String detail = violations.stream()
                .limit(10)
                .map(ValidationMessage::getMessage)
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
        checks.add(VerificationReport.Check.fail("schema", violations.size() + " violation(s): " + detail));
        warnings.add("Output does not match the target schema: " + detail);
        return false;
    }

    // ------------------------------------------------------------------ per invoice

    private void verifyInvoice(JsonNode invoice, String prefix, List<VerificationReport.Check> checks,
                               List<String> warnings, List<String> ungrounded, String normalizedText,
                               String digitsText, boolean textLayerUsable, int[] counters) {

        JsonNode header = invoice.path("DEFN_PROFORMA_INVOICE");
        JsonNode items = invoice.path("DEFN_PROFORMA_INVOICE_HSC");

        checkLineArithmetic(items, prefix, checks, warnings);
        BigDecimal itemSum = checkTotalReconciliation(header, items, prefix, checks, warnings);

        if (!textLayerUsable) {
            return;
        }

        header.properties().forEach(entry -> {
            if (DERIVED_HEADER_FIELDS.contains(entry.getKey())) {
                return;
            }
            checkGrounded(prefix + "DEFN_PROFORMA_INVOICE." + entry.getKey(), entry.getValue(),
                    normalizedText, digitsText, ungrounded, counters);
        });

        // currency gets its own test: the document may print "US$" where we output "USD".
        checkCurrencyGrounded(header, prefix, normalizedText, ungrounded, counters);
        // header total is grounded either by appearing in the text or by reconciling with the items.
        checkHeaderTotalGrounded(header, itemSum, prefix, digitsText, ungrounded, counters);

        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            final String itemPrefix = prefix + "DEFN_PROFORMA_INVOICE_HSC[" + i + "].";
            item.properties().forEach(entry -> {
                if (DERIVED_ITEM_FIELDS.contains(entry.getKey())) {
                    return;
                }
                checkGrounded(itemPrefix + entry.getKey(), entry.getValue(),
                        normalizedText, digitsText, ungrounded, counters);
            });
        }
    }

    // ------------------------------------------------------------------ arithmetic

    private void checkLineArithmetic(JsonNode items, String prefix,
                                     List<VerificationReport.Check> checks, List<String> warnings) {
        List<String> mismatches = new ArrayList<>();
        int checked = 0;

        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            BigDecimal quantity = decimal(item.get("quantity"));
            BigDecimal unitPrice = decimal(item.get("unit_price"));
            BigDecimal lineTotal = decimal(item.get("total_amount"));
            if (quantity == null || unitPrice == null || lineTotal == null) {
                continue;
            }
            checked++;
            BigDecimal expected = quantity.multiply(unitPrice);
            if (!withinTolerance(expected, lineTotal)) {
                mismatches.add(String.format(Locale.ROOT, "row %d: %s x %s = %s but total_amount is %s",
                        i + 1, quantity.toPlainString(), unitPrice.toPlainString(),
                        expected.setScale(2, RoundingMode.HALF_UP).toPlainString(), lineTotal.toPlainString()));
            }
        }

        if (checked == 0) {
            return;
        }
        String name = prefix + "line-total-arithmetic";
        if (mismatches.isEmpty()) {
            checks.add(VerificationReport.Check.pass(name, checked + " line total(s) reconcile"));
        } else {
            String detail = String.join("; ", mismatches);
            checks.add(VerificationReport.Check.fail(name, detail));
            warnings.add("Line totals do not reconcile with quantity x unit price - " + detail
                    + ". Values are reported exactly as extracted; nothing was recalculated.");
        }
    }

    /** @return the sum of line totals, or null when there was nothing to sum */
    private BigDecimal checkTotalReconciliation(JsonNode header, JsonNode items, String prefix,
                                                List<VerificationReport.Check> checks, List<String> warnings) {
        BigDecimal sum = null;
        for (JsonNode item : items) {
            BigDecimal lineTotal = decimal(item.get("total_amount"));
            if (lineTotal != null) {
                sum = sum == null ? lineTotal : sum.add(lineTotal);
            }
        }
        BigDecimal headerTotal = decimal(header.get("total_amount"));
        if (sum == null || headerTotal == null) {
            return sum;
        }

        String name = prefix + "invoice-total-arithmetic";
        if (withinTolerance(sum, headerTotal)) {
            checks.add(VerificationReport.Check.pass(name,
                    "Header total " + headerTotal.toPlainString() + " matches the sum of line totals"));
        } else {
            String detail = "Header total_amount is " + headerTotal.toPlainString()
                    + " but the line totals sum to " + sum.setScale(2, RoundingMode.HALF_UP).toPlainString();
            checks.add(VerificationReport.Check.fail(name, detail));
            warnings.add(detail + ". Both are reported as extracted; neither was adjusted.");
        }
        return sum;
    }

    private boolean withinTolerance(BigDecimal expected, BigDecimal actual) {
        BigDecimal difference = expected.subtract(actual).abs();
        BigDecimal allowed = expected.abs().multiply(RELATIVE_TOLERANCE).max(ABSOLUTE_TOLERANCE);
        return difference.compareTo(allowed) <= 0;
    }

    // ------------------------------------------------------------------ grounding

    private void checkGrounded(String path, JsonNode value, String normalizedText, String digitsText,
                               List<String> ungrounded, int[] counters) {
        if (value == null || value.isNull() || value.isContainerNode()) {
            return;
        }
        String raw = value.asText("");
        if (raw.isBlank()) {
            return;
        }

        counters[0]++;
        if (isGrounded(value, raw, normalizedText, digitsText)) {
            counters[1]++;
        } else {
            ungrounded.add(path);
        }
    }

    private boolean isGrounded(JsonNode value, String raw, String normalizedText, String digitsText) {
        if (value.isNumber()) {
            return digitsText.contains(significantDigits(raw));
        }

        String normalized = normalizeForSearch(raw);
        if (normalized.isEmpty()) {
            return true;
        }
        if (normalizedText.contains(normalized)) {
            return true;
        }
        // Long free-text fields (addresses, remarks) get re-flowed differently by the text
        // extractor; matching a distinctive prefix is the honest test for those.
        if (normalized.length() > 40 && normalizedText.contains(normalized.substring(0, 40))) {
            return true;
        }
        // Dates normalised to ISO will not appear verbatim; require every digit group instead.
        if (looksLikeIsoDate(raw)) {
            return isoDateGrounded(raw, normalizedText, digitsText);
        }
        // Numbers that arrived as strings.
        String digits = significantDigits(raw);
        return !digits.isEmpty() && digitsText.contains(digits);
    }

    private void checkCurrencyGrounded(JsonNode header, String prefix, String normalizedText,
                                       List<String> ungrounded, int[] counters) {
        JsonNode currency = header.get("currency");
        if (currency == null || currency.isNull() || currency.asText("").isBlank()) {
            return;
        }
        counters[0]++;
        String code = currency.asText().trim().toUpperCase(Locale.ROOT);
        List<String> aliases = CURRENCY_ALIASES.getOrDefault(code, List.of(code.toLowerCase(Locale.ROOT)));
        boolean found = aliases.stream().anyMatch(alias -> normalizedText.contains(normalizeForSearch(alias)));
        if (found) {
            counters[1]++;
        } else {
            ungrounded.add(prefix + "DEFN_PROFORMA_INVOICE.currency");
        }
    }

    private void checkHeaderTotalGrounded(JsonNode header, BigDecimal itemSum, String prefix,
                                          String digitsText, List<String> ungrounded, int[] counters) {
        BigDecimal headerTotal = decimal(header.get("total_amount"));
        if (headerTotal == null) {
            return;
        }
        counters[0]++;
        boolean printed = digitsText.contains(significantDigits(headerTotal.toPlainString()));
        boolean reconciles = itemSum != null && withinTolerance(itemSum, headerTotal);
        if (printed || reconciles) {
            counters[1]++;
        } else {
            ungrounded.add(prefix + "DEFN_PROFORMA_INVOICE.total_amount");
        }
    }

    private boolean looksLikeIsoDate(String raw) {
        return raw.length() == 10 && raw.charAt(4) == '-' && raw.charAt(7) == '-';
    }

    private boolean isoDateGrounded(String isoDate, String normalizedText, String digitsText) {
        String[] parts = isoDate.split("-");
        if (parts.length != 3) {
            return false;
        }
        String year = parts[0];
        String month = parts[1];
        String day = parts[2];
        if (!digitsText.contains(year) && !digitsText.contains(year.substring(2))) {
            return false;
        }
        boolean dayPresent = digitsText.contains(day) || digitsText.contains(stripLeadingZero(day));
        boolean monthPresent = digitsText.contains(month)
                || digitsText.contains(stripLeadingZero(month))
                || normalizedText.contains(monthName(month));
        return dayPresent && monthPresent;
    }

    private String stripLeadingZero(String value) {
        return value.startsWith("0") ? value.substring(1) : value;
    }

    private String monthName(String month) {
        return switch (month) {
            case "01" -> "jan";
            case "02" -> "feb";
            case "03" -> "mar";
            case "04" -> "apr";
            case "05" -> "may";
            case "06" -> "jun";
            case "07" -> "jul";
            case "08" -> "aug";
            case "09" -> "sep";
            case "10" -> "oct";
            case "11" -> "nov";
            case "12" -> "dec";
            default -> " ";
        };
    }

    // ------------------------------------------------------------------ helpers

    /** Lowercase, letters and digits only - immune to the whitespace and punctuation that PDF text extraction invents. */
    static String normalizeForSearch(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '$' || c == '€' || c == '£' || c == '¥' || c == '৳') {
                normalized.append(c);
            }
        }
        return normalized.toString();
    }

    /** Digits only, so "1,958.07" in the document matches 1958.07 in the extraction. */
    static String digitsOnly(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            }
        }
        return digits.toString();
    }

    /** Digits of a number with a trailing all-zero fraction removed, so 1200.00 matches "1,200". */
    static String significantDigits(String raw) {
        String trimmed = raw.trim();
        int dot = trimmed.indexOf('.');
        if (dot >= 0) {
            String fraction = trimmed.substring(dot + 1);
            if (fraction.chars().allMatch(c -> c == '0')) {
                trimmed = trimmed.substring(0, dot);
            }
        }
        return digitsOnly(trimmed);
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        try {
            String cleaned = node.asText("").replaceAll("[^0-9.\\-]", "");
            return cleaned.isBlank() ? null : new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonSchema loadSchema(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(SCHEMA_PATH).getInputStream()) {
            JsonNode schemaNode = objectMapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
        } catch (Exception e) {
            log.error("Could not load {} - schema validation will be reported as failed", SCHEMA_PATH, e);
            return null;
        }
    }
}
