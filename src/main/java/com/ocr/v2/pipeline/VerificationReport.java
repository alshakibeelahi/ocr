package com.ocr.v2.pipeline;

import java.util.List;

/**
 * The result of checking an extraction against the document it came from.
 *
 * <p>Nothing here rewrites a value. Every check reports, and the caller decides. For financial data
 * a silent correction is worse than a visible discrepancy: the discrepancy gets reviewed, the
 * correction gets trusted.
 *
 * @param status              PASS when every check passed, WARN when at least one did not
 * @param groundingAvailable  false for scans, where there is no text layer to check values against;
 *                            an empty {@code ungroundedFields} then means "not checked", not "verified"
 * @param checks              every check that ran, passed or not
 * @param warnings            human-readable summary of what did not pass
 * @param ungroundedFields    extracted values that could not be found verbatim in the document text
 */
public record VerificationReport(
        Status status,
        boolean schemaValid,
        boolean groundingAvailable,
        int fieldsChecked,
        int fieldsGrounded,
        List<Check> checks,
        List<String> warnings,
        List<String> ungroundedFields
) {

    public enum Status {
        /** Every check that could run, passed. */
        PASS,
        /** At least one check did not pass. The extraction is returned anyway, flagged. */
        WARN
    }

    /**
     * @param name    stable identifier, e.g. {@code schema}, {@code line-total-arithmetic}
     * @param passed  whether the check passed
     * @param detail  what was compared, in enough detail for a reviewer to act on it
     */
    public record Check(String name, boolean passed, String detail) {

        public static Check pass(String name, String detail) {
            return new Check(name, true, detail);
        }

        public static Check fail(String name, String detail) {
            return new Check(name, false, detail);
        }
    }

    public static VerificationReport notRun(String reason) {
        return new VerificationReport(Status.WARN, false, false, 0, 0,
                List.of(Check.fail("verification", reason)), List.of(reason), List.of());
    }
}
