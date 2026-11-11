package com.unfurl.dcp.componentconfig;

/**
 * Internal signal: a raw value does not satisfy a configuration type or bound. It carries only the rule that was
 * broken, never the value, and says whether the rule is the {@code secret://} reference rule (reported as
 * {@link ConfigFailureCode#CONFIG_SECRET_INLINE}) or an ordinary type or bound ({@link ConfigFailureCode#CONFIG_INVALID}).
 */
final class ValueRejection extends Exception {
    private final boolean secretInline;

    /** @param rule the rule the value must satisfy, for example {@code must be an integer} */
    ValueRejection(String rule) {
        this(rule, false);
    }

    /** As above; {@code secretInline} marks the {@code secret://} reference rule. */
    ValueRejection(String rule, boolean secretInline) {
        super(rule, null, false, false);
        this.secretInline = secretInline;
    }

    /** Returns the failure code this rejection maps to. */
    ConfigFailureCode code() {
        return secretInline ? ConfigFailureCode.CONFIG_SECRET_INLINE : ConfigFailureCode.CONFIG_INVALID;
    }
}
