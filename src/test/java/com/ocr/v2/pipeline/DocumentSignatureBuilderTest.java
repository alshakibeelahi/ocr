package com.ocr.v2.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signature is the retrieval key, so the property that matters most is what it does NOT
 * contain: values. Retrieving on a value would be the first step towards answering one invoice with
 * another invoice's data.
 */
class DocumentSignatureBuilderTest {

    private final DocumentSignatureBuilder builder = new DocumentSignatureBuilder();

    private static final String TEXT_LAYER = """
            ACME TEXTILES LTD
            12 Mill Road, Dhaka
            PROFORMA INVOICE
            PI No: PI-2025/0042      Date: 15-Mar-2025
            SL  STYLE  DESCRIPTION  QTY  UNIT  UNIT PRICE (US$)  AMOUNT
            1   ST-1001  Cotton Shirts  1000  PCS  2.50  2500.00
            """;

    @Test
    @DisplayName("captures letterhead, document type, column headers and currency tokens")
    void capturesStructure() {
        DocumentSignature signature = builder.fromTextLayer(TEXT_LAYER);

        assertThat(signature.source()).isEqualTo(DocumentSignature.Source.TEXT_LAYER);
        assertThat(signature.documentTypeHints()).anyMatch(hint -> hint.equalsIgnoreCase("PROFORMA INVOICE"));
        assertThat(signature.issuerCandidates()).contains("ACME TEXTILES LTD");
        assertThat(signature.columnHeaders()).isNotEmpty();
        assertThat(signature.currencyTokens()).isNotEmpty();
    }

    @Test
    @DisplayName("the retrieval text carries no values from the document, only labels")
    void carriesNoValues() {
        DocumentSignature signature = builder.fromTextLayer(TEXT_LAYER);

        assertThat(signature.text()).doesNotContain("2500.00");
        assertThat(signature.text()).doesNotContain("PI-2025/0042");
        assertThat(signature.text()).doesNotContain("2025");
        // The label survives - it identifies the template - while the value does not.
        assertThat(signature.text()).contains("PI No:");
    }

    @Test
    @DisplayName("two invoices from the same template produce the same signature")
    void identicalTemplateSameSignature() {
        String second = TEXT_LAYER
                .replace("PI-2025/0042", "PI-2025/0099")
                .replace("15-Mar-2025", "02-Apr-2025")
                .replace("2500.00", "9900.00")
                .replace("1000", "4000");

        assertThat(builder.fromTextLayer(second).text())
                .isEqualTo(builder.fromTextLayer(TEXT_LAYER).text());
    }

    @Test
    @DisplayName("letterhead collection stops once the item table starts")
    void stopsAtTheTable() {
        DocumentSignature signature = builder.fromTextLayer(TEXT_LAYER);

        assertThat(signature.issuerCandidates())
                .noneMatch(line -> line.startsWith("1   ST-1001"));
    }

    @Test
    @DisplayName("empty or missing text yields an explicit 'no signature'")
    void emptyInput() {
        assertThat(builder.fromTextLayer(null).source()).isEqualTo(DocumentSignature.Source.NONE);
        assertThat(builder.fromTextLayer("   ").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the vision fallback summary becomes a usable signature")
    void visionFallback() {
        DocumentSignature signature = builder.fromVisionSummary("""
                Document type: PROFORMA INVOICE
                Letterhead: ACME TEXTILES LTD
                Columns: SL, STYLE, QTY, UNIT PRICE (USD), AMOUNT
                """);

        assertThat(signature.source()).isEqualTo(DocumentSignature.Source.VISION);
        assertThat(signature.currencyTokens()).contains("USD");
        assertThat(signature.isEmpty()).isFalse();
    }
}
