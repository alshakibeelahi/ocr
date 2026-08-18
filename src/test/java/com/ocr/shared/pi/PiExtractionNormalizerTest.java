package com.ocr.shared.pi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression net for the normalisation rules shared by v1 and v2.
 *
 * <p>This logic moved out of {@code StartPiDataExtractionJobHandler} unchanged. These tests pin the
 * behaviour so the move - and anything that touches it later - cannot silently alter what v1
 * returns for a given model response.
 */
class PiExtractionNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PiExtractionNormalizer normalizer = new PiExtractionNormalizer(objectMapper);

    @Test
    @DisplayName("summary row is removed and its amount becomes the invoice total")
    void removesSummaryRowIntoHeaderTotal() throws Exception {
        String input = """
                {"DEFN_PROFORMA_INVOICE":{"total_amount":null},
                 "DEFN_PROFORMA_INVOICE_HSC":[
                   {"style_no":"ST-1","description":"Shirts","total_amount":100.5,"unit_price":2.0},
                   {"style_no":"ST-2","description":"Trousers","total_amount":49.5,"unit_price":3.0},
                   {"style_no":null,"description":"TOTAL","total_amount":150.0}
                 ]}
                """;

        JsonNode result = objectMapper.readTree(normalizer.normalize(input));

        assertThat(result.path("DEFN_PROFORMA_INVOICE_HSC")).hasSize(2);
        assertThat(result.path("DEFN_PROFORMA_INVOICE").path("total_amount").decimalValue())
                .isEqualByComparingTo("150.0");
    }

    @Test
    @DisplayName("header total falls back to the sum of line totals when no summary row exists")
    void sumsLineTotalsWhenHeaderTotalMissing() throws Exception {
        String input = """
                {"DEFN_PROFORMA_INVOICE":{},
                 "DEFN_PROFORMA_INVOICE_HSC":[
                   {"style_no":"A","total_amount":100.25},
                   {"style_no":"B","total_amount":50.25}
                 ]}
                """;

        JsonNode result = objectMapper.readTree(normalizer.normalize(input));

        assertThat(result.path("DEFN_PROFORMA_INVOICE").path("total_amount").decimalValue())
                .isEqualByComparingTo("150.50");
    }

    @Test
    @DisplayName("a labelled HS code propagates to every item row that lacks one")
    void propagatesLabelledHsCode() throws Exception {
        String input = """
                {"DEFN_PROFORMA_INVOICE":{"remarks":"H.S CODE : 4819.10.00"},
                 "DEFN_PROFORMA_INVOICE_HSC":[
                   {"style_no":"A","total_amount":10},
                   {"style_no":"B","total_amount":20,"hs_code":"6109.10"}
                 ]}
                """;

        JsonNode items = objectMapper.readTree(normalizer.normalize(input)).path("DEFN_PROFORMA_INVOICE_HSC");

        assertThat(items.get(0).path("hs_code").asText()).isEqualTo("4819.10.00");
        assertThat(items.get(1).path("hs_code").asText()).isEqualTo("6109.10");
    }

    @Test
    @DisplayName("a bare decimal amount is never mistaken for an HS code")
    void doesNotTreatAmountsAsHsCodes() throws Exception {
        String input = """
                {"DEFN_PROFORMA_INVOICE":{"total_amount":5945.44},
                 "DEFN_PROFORMA_INVOICE_HSC":[{"style_no":"A","total_amount":5945.44}]}
                """;

        JsonNode item = objectMapper.readTree(normalizer.normalize(input))
                .path("DEFN_PROFORMA_INVOICE_HSC").get(0);

        assertThat(item.path("hs_code").isNull() || !item.has("hs_code")).isTrue();
    }

    @Test
    @DisplayName("item numbering is rebuilt after summary rows are dropped")
    void renumbersItems() throws Exception {
        String input = """
                {"DEFN_PROFORMA_INVOICE":{},
                 "DEFN_PROFORMA_INVOICE_HSC":[
                   {"sl_no":7,"style_no":"A","total_amount":1},
                   {"style_no":null,"description":"Sub Total","total_amount":9},
                   {"sl_no":9,"style_no":"B","total_amount":2}
                 ]}
                """;

        JsonNode items = objectMapper.readTree(normalizer.normalize(input)).path("DEFN_PROFORMA_INVOICE_HSC");

        assertThat(items.get(0).path("sl_no").asInt()).isEqualTo(1);
        assertThat(items.get(1).path("sl_no").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("formatted currency strings become plain JSON numbers")
    void coercesFormattedAmounts() throws Exception {
        String input = """
                {"DEFN_PROFORMA_INVOICE":{"total_amount":"$ 1,958.07"},
                 "DEFN_PROFORMA_INVOICE_HSC":[{"style_no":"A","unit_price":"US$ 2.50","total_amount":"1,958.07"}]}
                """;

        JsonNode result = objectMapper.readTree(normalizer.normalize(input));

        assertThat(result.path("DEFN_PROFORMA_INVOICE").path("total_amount").decimalValue())
                .isEqualByComparingTo("1958.07");
        JsonNode item = result.path("DEFN_PROFORMA_INVOICE_HSC").get(0);
        assertThat(item.path("unit_price").decimalValue()).isEqualByComparingTo("2.50");
        assertThat(item.path("total_amount").decimalValue()).isEqualByComparingTo("1958.07");
    }

    @Test
    @DisplayName("draft_at_days is derived from the payment tenor when the model left it null")
    void derivesDraftAtDays() throws Exception {
        String input = """
                {"DEFN_PROFORMA_INVOICE":{"payment_terms":"LC AT 120 DAYS SIGHT","draft_at_days":null},
                 "DEFN_PROFORMA_INVOICE_HSC":[]}
                """;

        JsonNode header = objectMapper.readTree(normalizer.normalize(input)).path("DEFN_PROFORMA_INVOICE");

        assertThat(header.path("draft_at_days").asText()).isEqualTo("120");
    }

    @Test
    @DisplayName("currency is inferred from document text and the inference is recorded")
    void infersCurrencyAndRecordsProvenance() throws Exception {
        String input = """
                {"DEFN_PROFORMA_INVOICE":{"currency":null,"payment_terms":"Payable in U.S. DOLLAR","content":{}},
                 "DEFN_PROFORMA_INVOICE_HSC":[]}
                """;

        JsonNode header = objectMapper.readTree(normalizer.normalize(input)).path("DEFN_PROFORMA_INVOICE");

        assertThat(header.path("currency").asText()).isEqualTo("USD");
        assertThat(header.path("content").path("CURRENCY_SOURCE").asText()).isEqualTo("inferred-from-text");
    }

    @Test
    @DisplayName("an explicit currency is never overwritten")
    void keepsExplicitCurrency() throws Exception {
        String input = """
                {"DEFN_PROFORMA_INVOICE":{"currency":"EUR","payment_terms":"Payable in U.S. DOLLAR"},
                 "DEFN_PROFORMA_INVOICE_HSC":[]}
                """;

        JsonNode header = objectMapper.readTree(normalizer.normalize(input)).path("DEFN_PROFORMA_INVOICE");

        assertThat(header.path("currency").asText()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("every invoice in a multi-PI document is normalised")
    void normalisesEachInvoiceInABundle() throws Exception {
        String input = """
                {"PROFORMA_INVOICES":[
                  {"unique_id":"pi-1","DEFN_PROFORMA_INVOICE":{},
                   "DEFN_PROFORMA_INVOICE_HSC":[{"style_no":"A","total_amount":10}]},
                  {"unique_id":"pi-2","DEFN_PROFORMA_INVOICE":{},
                   "DEFN_PROFORMA_INVOICE_HSC":[{"style_no":"B","total_amount":20}]}
                ]}
                """;

        JsonNode invoices = objectMapper.readTree(normalizer.normalize(input)).path("PROFORMA_INVOICES");

        assertThat(invoices.get(0).path("DEFN_PROFORMA_INVOICE").path("total_amount").decimalValue())
                .isEqualByComparingTo("10");
        assertThat(invoices.get(1).path("DEFN_PROFORMA_INVOICE").path("total_amount").decimalValue())
                .isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("unparseable model output is returned untouched rather than repaired")
    void returnsNonJsonUnchanged() {
        String input = "I could not read this document.";
        assertThat(normalizer.normalize(input)).isEqualTo(input);
    }
}
