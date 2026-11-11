package com.unfurl.dcp.componentconfig;

import java.util.Objects;

/**
 * Value Object: one configuration failure. {@code key} is the configuration key it concerns, or {@code $} for the
 * packaged file as a whole. {@code detail} names the rule that was broken and never repeats a configuration value, so a
 * secret typed into the wrong place is not echoed into logs or reports.
 *
 * @param code   the failure code
 * @param key    the configuration key, or {@code $}
 * @param detail the broken rule, without values
 */
public record ConfigFailure(ConfigFailureCode code, String key, String detail) {
    /** The key used for failures about the packaged file as a whole. */
    public static final String DOCUMENT = "$";

    /** Validating constructor: every part is required. */
    public ConfigFailure {
        Objects.requireNonNull(code, "code");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("failure key is required");
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("failure detail is required");
    }

    /** Renders {@code CODE [key]: detail}. */
    @Override
    public String toString() {
        return code + " [" + key + "]: " + detail;
    }
}
