package com.ocr.v2.pipeline;

import com.ocr.v2.config.V2Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a {@link DocumentSignature} from the document's own text layer - no LLM call, no cost.
 *
 * <p>Most Proforma Invoices arrive as digitally generated PDFs, so this covers the common case for
 * free. Scans fall back to a small vision call driven by {@link ExtractionPipeline}.
 *
 * <p>Everything collected here is structural. Amounts, invoice numbers and dates are explicitly
 * excluded: retrieving knowledge on a value would be the first step towards answering one invoice
 * with another invoice's data.
 */
@V2Component
public class DocumentSignatureBuilder {

    private static final int MAX_HEADER_LINES = 12;
    private static final int MAX_SIGNATURE_CHARS = 1200;

    /** Words that, appearing together on one line, mark it as an item-table header row. */
    private static final Set<String> COLUMN_WORDS = Set.of(
            "sl", "sr", "no", "item", "style", "art", "article", "description", "goods", "particulars",
            "qty", "quantity", "unit", "uom", "rate", "price", "amount", "value", "total", "hs", "code",
            "weight", "net", "gross", "pack", "carton", "size", "colour", "color");

    private static final Pattern CURRENCY_TOKEN = Pattern.compile(
            "(?i)\\b(USD|EUR|GBP|BDT|CNY|INR|JPY|AUD|CAD|SGD|CHF|HKD|AED)\\b|US\\s?\\$|U\\.S\\.\\s?DOLLAR|[€£¥৳]");

    private static final Pattern DOC_TYPE = Pattern.compile(
            "(?i)\\b(proforma\\s+invoice|pro-forma\\s+invoice|commercial\\s+invoice|sales\\s+contract"
                    + "|purchase\\s+order|packing\\s+list|quotation|indent)\\b");

    /** Lines that are pure noise for identification purposes. */
    private static final Pattern MOSTLY_DIGITS = Pattern.compile("^[^\\p{L}]*$");

    /**
     * A written date, masked as a unit so the month name goes with it. Handled before the generic
     * digit mask, otherwise "15-Mar-2025" would survive as "#-Mar-#" and two invoices from the same
     * template would still produce different signatures.
     */
    private static final Pattern DATE_LIKE = Pattern.compile(
            "(?i)\\b\\d{1,4}[-/.\\s](?:\\d{1,2}|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"
                    + "[-/.\\s]\\d{2,4}\\b"
                    + "|(?i)\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2},?\\s+\\d{2,4}\\b");

    /** Any run of two or more digits - an invoice number, an amount, a phone number. Always masked. */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{2,}");

    public DocumentSignature fromTextLayer(String textLayer) {
        if (textLayer == null || textLayer.isBlank()) {
            return DocumentSignature.none();
        }

        List<String> lines = textLayer.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .toList();

        List<String> issuerCandidates = issuerCandidates(lines);
        List<String> columnHeaders = columnHeaders(lines);
        List<String> currencyTokens = matches(CURRENCY_TOKEN, textLayer);
        List<String> documentTypeHints = matches(DOC_TYPE, textLayer);

        String text = render(issuerCandidates, columnHeaders, currencyTokens, documentTypeHints);
        if (text.isBlank()) {
            return DocumentSignature.none();
        }
        return new DocumentSignature(text, issuerCandidates, columnHeaders, currencyTokens,
                documentTypeHints, DocumentSignature.Source.TEXT_LAYER);
    }

    /** Builds a signature out of the short answer returned by the vision fallback pass. */
    public DocumentSignature fromVisionSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return DocumentSignature.none();
        }
        String trimmed = truncate(maskValues(summary.strip()), MAX_SIGNATURE_CHARS);
        return new DocumentSignature(
                trimmed,
                List.of(),
                List.of(),
                matches(CURRENCY_TOKEN, trimmed),
                matches(DOC_TYPE, trimmed),
                DocumentSignature.Source.VISION);
    }

    /**
     * The letterhead and the addressee block - the two things that identify a template. Taken from
     * the top of the document, where both reliably sit.
     */
    private List<String> issuerCandidates(List<String> lines) {
        List<String> candidates = new ArrayList<>();
        for (String line : lines.subList(0, Math.min(lines.size(), 30))) {
            if (candidates.size() >= MAX_HEADER_LINES) {
                break;
            }
            if (line.length() < 3 || line.length() > 120 || MOSTLY_DIGITS.matcher(line).matches()) {
                continue;
            }
            if (looksLikeColumnHeader(line)) {
                // The table has started; nothing above it is letterhead any more.
                break;
            }
            candidates.add(maskValues(line));
        }
        return candidates;
    }

    /**
     * Masks every multi-digit run before the line is kept.
     *
     * <p>"PI No: PI-2025/0042  Date: 15-Mar-2025" becomes "PI No: PI-#/#  Date: #-Mar-#". The
     * labels survive - they are a real signal about the template - while the values do not. This
     * matters twice over: a signature carrying an invoice number would embed document-unique noise
     * that degrades every similarity search, and it would put a customer's reference numbers into
     * the retrieval query.
     */
    private String maskValues(String line) {
        String masked = DATE_LIKE.matcher(line).replaceAll("#");
        return DIGIT_RUN.matcher(masked).replaceAll("#");
    }

    private List<String> columnHeaders(List<String> lines) {
        Set<String> headers = new LinkedHashSet<>();
        for (String line : lines) {
            if (looksLikeColumnHeader(line)) {
                headers.add(truncate(maskValues(line), 160));
            }
            if (headers.size() >= 4) {
                break;
            }
        }
        return List.copyOf(headers);
    }

    private boolean looksLikeColumnHeader(String line) {
        String[] tokens = line.toLowerCase().split("[^a-z]+");
        int hits = 0;
        for (String token : tokens) {
            if (COLUMN_WORDS.contains(token)) {
                hits++;
            }
        }
        return hits >= 3;
    }

    private List<String> matches(Pattern pattern, String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && found.size() < 8) {
            found.add(matcher.group().strip());
        }
        return List.copyOf(found);
    }

    private String render(List<String> issuerCandidates, List<String> columnHeaders,
                          List<String> currencyTokens, List<String> documentTypeHints) {
        StringBuilder text = new StringBuilder();
        if (!documentTypeHints.isEmpty()) {
            text.append("Document type: ").append(String.join(", ", documentTypeHints)).append('\n');
        }
        if (!issuerCandidates.isEmpty()) {
            text.append("Header block:\n").append(String.join("\n", issuerCandidates)).append('\n');
        }
        if (!columnHeaders.isEmpty()) {
            text.append("Table columns:\n").append(String.join("\n", columnHeaders)).append('\n');
        }
        if (!currencyTokens.isEmpty()) {
            text.append("Currency tokens: ").append(String.join(", ", currencyTokens)).append('\n');
        }
        return truncate(text.toString().strip(), MAX_SIGNATURE_CHARS);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
