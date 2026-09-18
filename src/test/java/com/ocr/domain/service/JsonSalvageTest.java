package com.ocr.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Salvage has one job and one rule: make the prefix parseable, and never add a value the model did
 * not produce. Each case below asserts both — that the result parses, and that what it says is
 * still only what was actually extracted.
 */
class JsonSalvageTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private JsonNode parse(String json) throws Exception {
    return objectMapper.readTree(json);
  }

  @Test
  void completeJson_isReturnedUnchanged() {
    String json = "{\"pi_no\":\"PI-1\",\"items\":[1,2,3]}";

    assertThat(JsonSalvage.close(json)).isEqualTo(json);
  }

  @Test
  void cutInsideAString_closesTheStringAndTheContainers() throws Exception {
    String cut = "{\"pi_no\":\"PI-1\",\"addr\":\"150 Industrial Area";

    JsonNode node = parse(JsonSalvage.close(cut));

    assertThat(node.get("pi_no").asText()).isEqualTo("PI-1");
    assertThat(node.get("addr").asText()).isEqualTo("150 Industrial Area");
  }

  @Test
  void cutAfterAKey_completesItAsNullRatherThanGuessing() throws Exception {
    String cut = "{\"pi_no\":\"PI-1\",\"currency\":";

    JsonNode node = parse(JsonSalvage.close(cut));

    assertThat(node.get("pi_no").asText()).isEqualTo("PI-1");
    assertThat(node.get("currency").isNull()).isTrue();
  }

  @Test
  void cutAfterAComma_dropsTheDanglingSeparator() throws Exception {
    String cut = "{\"pi_no\":\"PI-1\",";

    JsonNode node = parse(JsonSalvage.close(cut));

    assertThat(node.size()).isEqualTo(1);
    assertThat(node.get("pi_no").asText()).isEqualTo("PI-1");
  }

  @Test
  void nestedArraysAndObjects_areClosedInTheRightOrder() throws Exception {
    String cut = "{\"PROFORMA_INVOICES\":[{\"DEFN_PROFORMA_INVOICE\":{\"pi_no\":\"PI-1\"},"
        + "\"DEFN_PROFORMA_INVOICE_HSC\":[{\"description\":\"Cotton";

    JsonNode node = parse(JsonSalvage.close(cut));

    JsonNode entry = node.get("PROFORMA_INVOICES").get(0);
    assertThat(entry.get("DEFN_PROFORMA_INVOICE").get("pi_no").asText()).isEqualTo("PI-1");
    assertThat(entry.get("DEFN_PROFORMA_INVOICE_HSC").get(0).get("description").asText())
        .isEqualTo("Cotton");
  }

  /** A cut landing on a backslash would otherwise turn the closing quote into an escape. */
  @Test
  void cutOnAnEscapeCharacter_doesNotProduceBrokenJson() throws Exception {
    String cut = "{\"remarks\":\"line one\\";

    JsonNode node = parse(JsonSalvage.close(cut));

    assertThat(node.get("remarks").asText()).isEqualTo("line one");
  }

  @Test
  void escapedQuoteInsideAString_isNotMistakenForTheEnd() throws Exception {
    String cut = "{\"remarks\":\"he said \\\"ship it\\\" and then";

    JsonNode node = parse(JsonSalvage.close(cut));

    assertThat(node.get("remarks").asText()).isEqualTo("he said \"ship it\" and then");
  }

  @Test
  void bracesInsideAString_areNotCountedAsStructure() throws Exception {
    String cut = "{\"remarks\":\"see clause {3} and [4]\",\"pi_no\":\"PI-2";

    JsonNode node = parse(JsonSalvage.close(cut));

    assertThat(node.get("remarks").asText()).isEqualTo("see clause {3} and [4]");
    assertThat(node.get("pi_no").asText()).isEqualTo("PI-2");
  }

  @Test
  void nullAndBlankInput_arePassedThrough() {
    assertThat(JsonSalvage.close(null)).isNull();
    assertThat(JsonSalvage.close("   ")).isEqualTo("   ");
  }

  @Test
  void truncatedRealExtraction_parsesAndKeepsEveryFieldThatArrived() throws Exception {
    // Shaped like the output that actually failed: good header fields, then a cut mid-address.
    String cut = """
        {
          "PROFORMA_INVOICES": [
            {
              "unique_id": "pi-1",
              "DEFN_PROFORMA_INVOICE": {
                "pi_no": "SPTL/881/2026/412",
                "pi_date": "05.05.2026",
                "applicant_name": "EVERBRIGHT SWEATER LTD.",
                "beneficiary_name": "SOIL PACKAGING & TRIMS LTD.",
                "beneficiary_addr": "150, 19961-201 & Psc Certified Company\
        """;

    JsonNode header = parse(JsonSalvage.close(cut))
        .get("PROFORMA_INVOICES").get(0).get("DEFN_PROFORMA_INVOICE");

    assertThat(header.get("pi_no").asText()).isEqualTo("SPTL/881/2026/412");
    assertThat(header.get("pi_date").asText()).isEqualTo("05.05.2026");
    assertThat(header.get("applicant_name").asText()).isEqualTo("EVERBRIGHT SWEATER LTD.");
    assertThat(header.get("beneficiary_name").asText()).isEqualTo("SOIL PACKAGING & TRIMS LTD.");
    assertThat(header.has("beneficiary_addr")).isTrue();
  }
}
