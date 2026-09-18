package com.ocr.domain.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Closes JSON that was cut off mid-document.
 *
 * <p>When generation is stopped early — because the model started looping, or because it ran into
 * the token ceiling — the text is valid JSON right up to the cut and unparseable only because the
 * brackets were never closed. The fields already extracted are real, so discarding them would throw
 * away most of an extraction over its last few characters. This reattaches the missing closers so
 * the prefix can be parsed and used, and the caller records that it had to.
 *
 * <p>It only ever closes what is open. It never invents a value, so a field the model did not reach
 * stays absent rather than becoming a plausible-looking guess.
 */
public final class JsonSalvage {

    private JsonSalvage() {
    }

    /**
     * @return the input with any unterminated string and open containers closed, or the input
     *     unchanged when nothing is open
     */
    public static String close(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }

        Deque<Character> open = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> open.push('}');
                case '[' -> open.push(']');
                case '}', ']' -> {
                    if (!open.isEmpty()) {
                        open.pop();
                    }
                }
                default -> {
                    // Structural characters only; values need no tracking.
                }
            }
        }

        if (!inString && open.isEmpty()) {
            return json;
        }

        StringBuilder out = new StringBuilder(json);

        // A cut inside an escape sequence would make the closing quote part of the escape.
        if (inString && escaped) {
            out.setLength(out.length() - 1);
        }
        if (inString) {
            out.append('"');
        }

        trimDanglingSeparator(out);

        while (!open.isEmpty()) {
            out.append(open.pop());
        }
        return out.toString();
    }

    /**
     * Drops a trailing comma, and completes a key whose value never arrived. Either would make the
     * reattached closer a syntax error.
     */
    private static void trimDanglingSeparator(StringBuilder out) {
        int end = out.length();
        while (end > 0 && Character.isWhitespace(out.charAt(end - 1))) {
            end--;
        }
        if (end == 0) {
            return;
        }
        char last = out.charAt(end - 1);
        if (last == ',') {
            out.setLength(end - 1);
        } else if (last == ':') {
            out.setLength(end);
            out.append(" null");
        } else {
            out.setLength(end);
        }
    }
}
