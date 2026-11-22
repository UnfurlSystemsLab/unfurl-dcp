package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Enumeration: how widely a documented surface may be shown (DCP HLD-C2 G.1 input 7). DCP records the value; product
 * policy decides it. {@link #PRIVATE} surfaces are never documented, and are reported as {@code POLICY_PRIVATE}.
 */
public enum Visibility {
    /** Documented for any reader. */
    PUBLIC,
    /** Documented for readers inside the deployment's organisation. */
    INTERNAL,
    /** Never documented. */
    PRIVATE;

    /** The lower-case wire value the DCP schemas list (HLD-C2 G.2, {@link WireEnums}). */
    @JsonValue
    public String wireValue() {
        return WireEnums.wire(this);
    }

    /**
     * Factory: reads a wire value, accepting only the lower-case schema spelling.
     *
     * @throws IllegalArgumentException for any other value
     */
    @JsonCreator
    public static Visibility fromWireValue(String value) {
        return WireEnums.parse(Visibility.class, value);
    }
}
