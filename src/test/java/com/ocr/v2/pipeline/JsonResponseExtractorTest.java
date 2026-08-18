package com.ocr.v2.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Providers without a native JSON mode (Anthropic, Bedrock Converse) wrap or preface their output.
 * This is what makes those providers usable without weakening the contract for the ones that do.
 */
class JsonResponseExtractorTest {

    private final JsonResponseExtractor extractor = new JsonResponseExtractor();

    @Test
    @DisplayName("plain JSON passes through unchanged")
    void plainJson() {
        assertThat(extractor.extract("{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("markdown fences are stripped")
    void markdownFence() {
        String response = """
                ```json
                {"a": 1}
                ```""";
        assertThat(extractor.extract(response)).isEqualTo("{\"a\": 1}");
    }

    @Test
    @DisplayName("prose before and after the object is discarded")
    void surroundingProse() {
        String response = "Here is the extraction:\n{\"a\": 1}\nLet me know if you need more.";
        assertThat(extractor.extract(response)).isEqualTo("{\"a\": 1}");
    }

    @Test
    @DisplayName("a brace inside a string value does not terminate the object early")
    void braceInsideStringValue() {
        String response = "{\"remarks\":\"see clause {3} of the contract\",\"b\":2}";
        assertThat(extractor.extract(response)).isEqualTo(response);
    }

    @Test
    @DisplayName("an escaped quote does not confuse string tracking")
    void escapedQuote() {
        String response = "{\"remarks\":\"he said \\\"ship it\\\" }\",\"b\":2}";
        assertThat(extractor.extract(response)).isEqualTo(response);
    }

    @Test
    @DisplayName("nested objects are kept whole")
    void nestedObjects() {
        String response = "prefix {\"a\":{\"b\":{\"c\":1}}} suffix";
        assertThat(extractor.extract(response)).isEqualTo("{\"a\":{\"b\":{\"c\":1}}}");
    }

    @Test
    @DisplayName("truncated output is handed on as-is so the parse error names the real problem")
    void truncatedOutput() {
        String response = "{\"a\": 1, \"b\": ";
        assertThat(extractor.extract(response)).isEqualTo("{\"a\": 1, \"b\":");
    }

    @Test
    @DisplayName("a response with no object at all is returned unchanged")
    void noJsonAtAll() {
        assertThat(extractor.extract("I cannot read this document.")).isEqualTo("I cannot read this document.");
    }

    @Test
    @DisplayName("null and blank are safe")
    void nullAndBlank() {
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract("   ")).isEmpty();
    }
}
