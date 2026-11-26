package com.unfurl.dcp.claim;

/** How a caller may authenticate to a component (DCP HLD-C2 A.9 {@code authentication.accepted_modes}). */
public enum AuthenticationMode {
    JWT,
    OIDC,
    API_KEY,
    MTLS,
    SESSION_COOKIE
}
