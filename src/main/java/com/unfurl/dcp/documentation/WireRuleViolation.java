package com.unfurl.dcp.documentation;

/**
 * Failure: a wire value or record invariant of the documentation package is broken. Its message is fixed text naming
 * the rule, the field or the enumeration, and never repeats a value from the input. Values read from a provider's
 * response are untrusted, and a decode error becomes an observation detail that projectors report and log (DCP HLD-C2
 * G.3, G.4), so the message must stay content-free.
 *
 * <p>{@link RegistrationReportCodec} forwards only this type's message (and the field name of a missing required
 * field) into its own error; every other parse failure is reported by field location alone.
 */
public final class WireRuleViolation extends IllegalArgumentException {
    /**
     * Constructor.
     *
     * @param rule fixed text stating the broken rule; callers must not include input values
     */
    public WireRuleViolation(String rule) {
        super(rule);
    }
}
