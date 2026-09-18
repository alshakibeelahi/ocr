package com.ocr.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The guard has to be decisive in both directions: missing a loop costs a half hour of inference,
 * and tripping on ordinary invoice text would truncate a perfectly good extraction. The cases below
 * are drawn from real output on both sides of that line.
 */
class RepetitionGuardTest {

  /** The unit the model actually looped on, verbatim. */
  private static final String LOOP_UNIT =
      "150, 19961-201 & Psc Certified Company, A Class of 1984 Export Oriented "
          + "Industries Packaging Factory, ";

  private boolean feed(RepetitionGuard guard, String text, int chunkSize) {
    boolean tripped = false;
    for (int i = 0; i < text.length(); i += chunkSize) {
      String chunk = text.substring(i, Math.min(text.length(), i + chunkSize));
      tripped |= guard.append(chunk);
      if (guard.isTripped()) {
        break;
      }
    }
    return tripped;
  }

  @Test
  void realWorldLoop_isDetected() {
    RepetitionGuard guard = new RepetitionGuard();
    String prefix = "{\"pi_no\":\"SPTL/881/2026/412\",\"beneficiary_addr\":\"";

    boolean tripped = feed(guard, prefix + LOOP_UNIT.repeat(40), 7);

    assertThat(tripped).isTrue();
    assertThat(guard.isTripped()).isTrue();
    assertThat(guard.period()).isGreaterThan(0);
  }

  @Test
  void loopIsTrimmedBackToASingleCopy() {
    RepetitionGuard guard = new RepetitionGuard();
    String prefix = "{\"addr\":\"";
    feed(guard, prefix + LOOP_UNIT.repeat(40), 11);

    String kept = guard.textWithoutLoop();

    assertThat(kept).startsWith(prefix);
    assertThat(kept.length()).isLessThan(guard.text().length());
    // One copy survives so the value still reads as written rather than being cut mid-word.
    assertThat(countOccurrences(kept, LOOP_UNIT.trim())).isEqualTo(1);
  }

  @Test
  void ordinaryInvoiceJson_doesNotTrip() {
    RepetitionGuard guard = new RepetitionGuard();
    String json = """
        {"PROFORMA_INVOICES":[{"unique_id":"pi-1","DEFN_PROFORMA_INVOICE":{
        "pi_no":"SPTL/881/2026/412","pi_date":"05.05.2026",
        "applicant_name":"EVERBRIGHT SWEATER LTD.","applicant_addr":"Kajaria Nazir, Dhaka",
        "beneficiary_name":"SOIL PACKAGING & TRIMS LTD.","currency":"USD",
        "payment_terms":"At 90 days sight","partial_shipment":"Allowed"},
        "DEFN_PROFORMA_INVOICE_HSC":[
        {"sl_no":1,"description":"Poly bag","quantity":1000,"unit_price":0.15,"total_amount":150.0},
        {"sl_no":2,"description":"Carton box","quantity":500,"unit_price":1.20,"total_amount":600.0},
        {"sl_no":3,"description":"Hang tag","quantity":2000,"unit_price":0.05,"total_amount":100.0}]}]}
        """;

    assertThat(feed(guard, json, 9)).isFalse();
    assertThat(guard.isTripped()).isFalse();
    assertThat(guard.textWithoutLoop()).isEqualTo(guard.text());
  }

  /** Repeated line items look similar but differ in every row, which is the distinction that matters. */
  @Test
  void manySimilarLineItems_doNotTrip() {
    StringBuilder json = new StringBuilder("{\"DEFN_PROFORMA_INVOICE_HSC\":[");
    for (int i = 1; i <= 40; i++) {
      json.append(String.format(
          "{\"sl_no\":%d,\"description\":\"Item %d\",\"quantity\":%d,\"unit_price\":%d.50},", i, i, i * 3, i));
    }
    json.append("]}");

    RepetitionGuard guard = new RepetitionGuard();

    assertThat(feed(guard, json.toString(), 13)).isFalse();
  }

  @Test
  void shortRepeatedPunctuation_doesNotTrip() {
    RepetitionGuard guard = new RepetitionGuard();

    assertThat(feed(guard, "{\"remarks\":\"----------------------------------------\"}", 5)).isFalse();
  }

  @Test
  void detectionSurvivesChunkBoundaries() {
    // Tokens arrive in arbitrary slices; the loop must be found regardless of where they split.
    for (int chunkSize : new int[] {1, 3, 17, 64, 501}) {
      RepetitionGuard guard = new RepetitionGuard();
      assertThat(feed(guard, "{\"a\":\"" + LOOP_UNIT.repeat(40), chunkSize))
          .as("chunk size %d", chunkSize)
          .isTrue();
    }
  }

  @Test
  void emptyAndNullChunks_areIgnored() {
    RepetitionGuard guard = new RepetitionGuard();

    assertThat(guard.append(null)).isFalse();
    assertThat(guard.append("")).isFalse();
    assertThat(guard.text()).isEmpty();
  }

  @Test
  void onceTripped_itStaysTrippedAndStopsAccumulating() {
    RepetitionGuard guard = new RepetitionGuard();
    feed(guard, "{\"a\":\"" + LOOP_UNIT.repeat(40), 16);
    int lengthAtTrip = guard.text().length();

    guard.append("more output that should be ignored");

    assertThat(guard.isTripped()).isTrue();
    assertThat(guard.text()).hasSize(lengthAtTrip);
  }

  private int countOccurrences(String haystack, String needle) {
    int count = 0;
    int index = haystack.indexOf(needle);
    while (index >= 0) {
      count++;
      index = haystack.indexOf(needle, index + needle.length());
    }
    return count;
  }
}
