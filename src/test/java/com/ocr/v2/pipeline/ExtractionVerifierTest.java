package com.ocr.v2.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checks that turn "the model said so" into "the document says so".
 *
 * <p>The behaviour these pin down is deliberately conservative: the verifier reports, it never
 * repairs. A test that expected a corrected value would be testing the wrong product.
 */
class ExtractionVerifierTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExtractionVerifier verifier = new ExtractionVerifier(objectMapper);

    private static final String TEXT_LAYER = """
            ACME TEXTILES LTD
            PROFORMA INVOICE
            PI No: PI-2025/0042        Date: 15-Mar-2025
            To: NORTHERN IMPORTS LTD
            SL  STYLE     DESCRIPTION      QTY   UNIT  UNIT PRICE (US$)  AMOUNT
            1   ST-1001   Cotton Shirts    1,000  PCS   2.50              2,500.00
            2   ST-1002   Denim Trousers     500  PCS   4.00              2,000.00
                                                    TOTAL                 4,500.00
            PAYMENT: LC AT 120 DAYS SIGHT
            H.S CODE : 6205.20.00
            """;

    private JsonNode extraction(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private String goodExtraction() {
        return """
                {"DEFN_PROFORMA_INVOICE":{
                   "pi_no":"PI-2025/0042","pi_date":"2025-03-15",
                   "applicant_name":"NORTHERN IMPORTS LTD","beneficiary_name":"ACME TEXTILES LTD",
                   "currency":"USD","payment_terms":"LC AT 120 DAYS SIGHT","draft_at_days":"120",
                   "total_amount":4500.00,"status":"Draft","content":null},
                 "DEFN_PROFORMA_INVOICE_HSC":[
                   {"sl_no":1,"style_no":"ST-1001","description":"Cotton Shirts","quantity":1000,
                    "quantity_unit":"PCS","unit_price":2.50,"total_amount":2500.00,"hs_code":"6205.20.00"},
                   {"sl_no":2,"style_no":"ST-1002","description":"Denim Trousers","quantity":500,
                    "quantity_unit":"PCS","unit_price":4.00,"total_amount":2000.00,"hs_code":"6205.20.00"}
                 ]}
                """;
    }

    @Test
    @DisplayName("a faithful extraction passes every check")
    void passesForFaithfulExtraction() throws Exception {
        VerificationReport report = verifier.verify(extraction(goodExtraction()), TEXT_LAYER, true);

        assertThat(report.status()).isEqualTo(VerificationReport.Status.PASS);
        assertThat(report.schemaValid()).isTrue();
        assertThat(report.groundingAvailable()).isTrue();
        assertThat(report.ungroundedFields()).isEmpty();
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    @DisplayName("a value that is not in the document is flagged, not corrected")
    void flagsHallucinatedValue() throws Exception {
        String tampered = goodExtraction().replace("\"PI-2025/0042\"", "\"PI-2025/9999\"");

        VerificationReport report = verifier.verify(extraction(tampered), TEXT_LAYER, true);

        assertThat(report.status()).isEqualTo(VerificationReport.Status.WARN);
        assertThat(report.ungroundedFields()).contains("DEFN_PROFORMA_INVOICE.pi_no");
        // The extraction itself is untouched - the caller decides what to do.
        assertThat(extraction(tampered).path("DEFN_PROFORMA_INVOICE").path("pi_no").asText())
                .isEqualTo("PI-2025/9999");
    }

    @Test
    @DisplayName("a line total that does not equal quantity x unit price is reported")
    void flagsLineArithmeticMismatch() throws Exception {
        String tampered = goodExtraction().replace("\"total_amount\":2500.00", "\"total_amount\":2600.00");

        VerificationReport report = verifier.verify(extraction(tampered), TEXT_LAYER, true);

        assertThat(report.status()).isEqualTo(VerificationReport.Status.WARN);
        assertThat(report.checks())
                .anyMatch(check -> check.name().contains("line-total-arithmetic") && !check.passed());
    }

    @Test
    @DisplayName("a header total that does not match the line totals is reported")
    void flagsInvoiceTotalMismatch() throws Exception {
        String tampered = goodExtraction().replace("\"total_amount\":4500.00", "\"total_amount\":5500.00");

        VerificationReport report = verifier.verify(extraction(tampered), TEXT_LAYER, true);

        assertThat(report.checks())
                .anyMatch(check -> check.name().contains("invoice-total-arithmetic") && !check.passed());
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("neither was adjusted"));
    }

    @Test
    @DisplayName("scans report grounding as unavailable rather than as passed")
    void reportsMissingTextLayerHonestly() throws Exception {
        VerificationReport report = verifier.verify(extraction(goodExtraction()), "", false);

        assertThat(report.groundingAvailable()).isFalse();
        assertThat(report.status()).isEqualTo(VerificationReport.Status.WARN);
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("no text layer"));
        // Nothing was checked, so nothing may be claimed as verified.
        assertThat(report.fieldsChecked()).isZero();
    }

    @Test
    @DisplayName("USD is grounded by a 'US$' column header, not only by the literal code")
    void groundsCurrencyViaAlias() throws Exception {
        VerificationReport report = verifier.verify(extraction(goodExtraction()), TEXT_LAYER, true);

        assertThat(report.ungroundedFields()).doesNotContain("DEFN_PROFORMA_INVOICE.currency");
    }

    @Test
    @DisplayName("an ISO-normalised date is grounded against the printed '15-Mar-2025'")
    void groundsNormalisedDate() throws Exception {
        VerificationReport report = verifier.verify(extraction(goodExtraction()), TEXT_LAYER, true);

        assertThat(report.ungroundedFields()).doesNotContain("DEFN_PROFORMA_INVOICE.pi_date");
    }

    @Test
    @DisplayName("output with the wrong shape fails schema validation")
    void failsOnWrongShape() throws Exception {
        VerificationReport report = verifier.verify(extraction("{\"something_else\":1}"), TEXT_LAYER, true);

        assertThat(report.schemaValid()).isFalse();
        assertThat(report.status()).isEqualTo(VerificationReport.Status.WARN);
    }

    @Test
    @DisplayName("1200.00 is grounded by '1,200' in the document")
    void matchesNumbersAcrossFormatting() {
        assertThat(ExtractionVerifier.significantDigits("1200.00")).isEqualTo("1200");
        assertThat(ExtractionVerifier.digitsOnly("US$ 1,200.00")).isEqualTo("120000");
        assertThat(ExtractionVerifier.digitsOnly("1,200")).contains("1200");
    }
}
