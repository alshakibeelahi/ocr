package com.ocr.v2.pipeline;

import com.ocr.v2.config.V2Component;

/**
 * Pulls the JSON object out of a model response.
 *
 * <p>Needed because not every provider has a native JSON mode - Anthropic and Bedrock Converse do
 * not - so a response can arrive wrapped in a markdown fence or preceded by a sentence of prose.
 * This finds the first balanced top-level object, respecting string literals and escapes so a brace
 * inside a value (an address, a remark) cannot terminate it early.
 *
 * <p>It never repairs malformed JSON. If what comes back is not parseable the caller surfaces that
 * as a failure rather than guessing at the intent.
 */
@V2Component
public class JsonResponseExtractor {

    public String extract(String response) {
        if (response == null) {
            return "";
        }
        String text = response.strip();
        if (text.isEmpty()) {
            return "";
        }

        text = stripCodeFence(text);

        int start = text.indexOf('{');
        if (start < 0) {
            return text;
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }

        // Unbalanced: hand back everything from the first brace so the parse error names the
        // real problem instead of a truncation this class invented.
        return text.substring(start);
    }

    private String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String body = text.substring(firstNewline + 1);
        int closing = body.lastIndexOf("```");
        return (closing < 0 ? body : body.substring(0, closing)).strip();
    }
}
