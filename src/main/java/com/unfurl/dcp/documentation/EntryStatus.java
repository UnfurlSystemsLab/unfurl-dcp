package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Enumeration: whether a documentation entry is documented right now (DCP HLD-C2 G.4). */
public enum EntryStatus {
    /** Documented; the reason is {@link ReasonCode#ACTIVE}. */
    ACTIVE,
    /** Not documented, for the reason given. */
    INACTIVE,
    /** An external surface: listed, never probed; the reason is {@link ReasonCode#EXTERNAL_SURFACE}. */
    NOT_PROBED;

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
    public static EntryStatus fromWireValue(String value) {
        return WireEnums.parse(EntryStatus.class, value);
    }
}
