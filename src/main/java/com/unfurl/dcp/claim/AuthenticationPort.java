package com.unfurl.dcp.claim;

import java.util.List;

/**
 * The authentication integration surface (DCP HLD-C2 A.9): whether the component requires an authenticated caller,
 * and how a caller may authenticate.
 *
 * <p>Pattern: immutable <b>value object</b> that keeps presence: every member is a wrapper or a nullable list, so a member
 * the claim did not write stays null and {@code ClaimValidator} can reject a missing required member (HLD-C2 A.9, "A
 * declared port is complete"); nothing is defaulted. A list is frozen when present and stays null when absent.
 *
 * @param required      required: whether the component requires an authenticated caller.
 * @param acceptedModes required: the modes a caller may use (an empty list is a value).
 * @param notes         optional free-text notes.
 */
public record AuthenticationPort(Boolean required, List<AuthenticationMode> acceptedModes, String notes) {
    /** Compact constructor: freezes the mode list when present. */
    public AuthenticationPort {
        acceptedModes = acceptedModes == null ? null : List.copyOf(acceptedModes);
    }
}
