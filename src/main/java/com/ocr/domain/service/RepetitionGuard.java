package com.ocr.domain.service;

/**
 * Watches a model's output for the point where it stops extracting and starts looping.
 *
 * <p>Sampling penalties make a loop unlikely, not impossible, and when one does happen there is
 * nothing in the protocol that ends it: the request runs until the token budget or the timeout does
 * it, and everything after the first repeat is noise. Catching it in the stream turns a half hour of
 * wasted inference into a few seconds, and — because the guard reports where the loop began — lets
 * the caller keep the good prefix instead of throwing the whole extraction away.
 *
 * <p>Detection is structural, not statistical: the tail of the output is scanned for a block that
 * repeats back to back. Legitimate invoice text does not repeat a 20+ character run four times in a
 * row, so a match is decisive rather than a heuristic worth second-guessing.
 */
public final class RepetitionGuard {

    /** Repeating units longer than this are not searched for; the observed ones are ~100 chars. */
    private static final int MAX_PERIOD = 400;
    /** Shorter than this and ordinary punctuation runs would trip the guard. */
    private static final int MIN_PERIOD = 16;
    /** Back-to-back occurrences before the output is called degenerate. */
    private static final int MIN_REPEATS = 4;
    /** Scanning every chunk would be wasteful; this is the granularity of the check. */
    private static final int CHECK_EVERY_CHARS = 400;

    private final StringBuilder text = new StringBuilder();
    private int charsSinceCheck;
    private int loopStartIndex = -1;
    private int period;

    /**
     * Feeds the next streamed chunk in.
     *
     * @return {@code true} the first time the accumulated output is found to be looping
     */
    public boolean append(String chunk) {
        if (chunk == null || chunk.isEmpty() || isTripped()) {
            return false;
        }
        text.append(chunk);
        charsSinceCheck += chunk.length();
        if (charsSinceCheck < CHECK_EVERY_CHARS) {
            return false;
        }
        charsSinceCheck = 0;
        return detect();
    }

    /** Whether a loop has been found. */
    public boolean isTripped() {
        return loopStartIndex >= 0;
    }

    /** Length of the repeating unit, or 0 if no loop was found. */
    public int period() {
        return period;
    }

    /**
     * Index in the accumulated output where the repetition began. Everything before it is the
     * usable part of the extraction.
     */
    public int loopStartIndex() {
        return loopStartIndex;
    }

    /** The accumulated output so far. */
    public String text() {
        return text.toString();
    }

    /**
     * The output with the repeated run removed, keeping one copy of the unit so the value still
     * reads as it was written rather than being cut mid-word.
     */
    public String textWithoutLoop() {
        if (!isTripped()) {
            return text.toString();
        }
        return text.substring(0, Math.min(text.length(), loopStartIndex + period));
    }

    private boolean detect() {
        int length = text.length();
        int maxPeriod = Math.min(MAX_PERIOD, length / MIN_REPEATS);
        for (int p = MIN_PERIOD; p <= maxPeriod; p++) {
            if (tailRepeats(p)) {
                period = p;
                loopStartIndex = findLoopStart(p);
                return true;
            }
        }
        return false;
    }

    /** True when the last {@code MIN_REPEATS} blocks of length {@code p} are all identical. */
    private boolean tailRepeats(int p) {
        int end = text.length();
        int span = p * MIN_REPEATS;
        if (span > end) {
            return false;
        }
        for (int offset = 0; offset < p; offset++) {
            char expected = text.charAt(end - span + offset);
            for (int repeat = 1; repeat < MIN_REPEATS; repeat++) {
                if (text.charAt(end - span + repeat * p + offset) != expected) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Walks the repeating unit backwards to the first occurrence, so the good prefix is kept whole. */
    private int findLoopStart(int p) {
        int start = text.length() - p * MIN_REPEATS;
        while (start - p >= 0 && matchesAt(start - p, start, p)) {
            start -= p;
        }
        return start;
    }

    private boolean matchesAt(int a, int b, int p) {
        for (int i = 0; i < p; i++) {
            if (text.charAt(a + i) != text.charAt(b + i)) {
                return false;
            }
        }
        return true;
    }
}
