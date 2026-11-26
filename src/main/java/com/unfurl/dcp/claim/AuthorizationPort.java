package com.unfurl.dcp.claim;

import java.util.List;

/**
 * The authorization integration surface (DCP HLD-C2 A.9): whether the component requires authorization checks, who
 * makes the decision, and the permission prefixes it uses.
 *
 * <p>Pattern: immutable <b>value object</b> that keeps presence: every member is a wrapper or a nullable list, so a member
 * the claim did not write stays null and {@code ClaimValidator} can reject a missing required member (HLD-C2 A.9, "A
 * declared port is complete"); nothing is defaulted. A list is frozen when present and stays null when absent.
 *
 * @param required           required: whether the component requires authorization checks.
 * @param decisionOwner      required: who decides.
 * @param permissionPrefixes optional: the permission prefixes the component checks.
 * @param notes              optional free-text notes.
 */
public record AuthorizationPort(Boolean required, DecisionOwner decisionOwner, List<String> permissionPrefixes,
                                String notes) {
    /** Compact constructor: freezes the prefix list when present. */
    public AuthorizationPort {
        permissionPrefixes = permissionPrefixes == null ? null : List.copyOf(permissionPrefixes);
    }
}
