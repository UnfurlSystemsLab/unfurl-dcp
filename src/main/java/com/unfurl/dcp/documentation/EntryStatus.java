package com.unfurl.dcp.documentation;

/** Enumeration: whether a documentation entry is documented right now (DCP HLD-C2 G.4). */
public enum EntryStatus {
    /** Documented; the reason is {@link ReasonCode#ACTIVE}. */
    ACTIVE,
    /** Not documented, for the reason given. */
    INACTIVE,
    /** An external surface: listed, never probed; the reason is {@link ReasonCode#EXTERNAL_SURFACE}. */
    NOT_PROBED
}
