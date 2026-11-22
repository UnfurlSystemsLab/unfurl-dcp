package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Enumeration: whether the verified composition contains an identity contract (DCP HLD-C2 G.1 input 6). It decides the
 * rendered security schemes and is never read from configuration.
 */
public enum IdentityMode {
    /** The composition has an identity-context contract: protected components render bearer security. */
    IN_CONTRACT,
    /** No identity contract: nothing renders security, and the output states the trusted-network mode. */
    ABSENT;

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
    public static IdentityMode fromWireValue(String value) {
        return WireEnums.parse(IdentityMode.class, value);
    }
}
