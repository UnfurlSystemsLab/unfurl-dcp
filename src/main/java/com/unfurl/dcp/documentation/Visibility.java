package com.unfurl.dcp.documentation;

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
    PRIVATE
}
