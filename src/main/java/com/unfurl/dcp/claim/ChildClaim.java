package com.unfurl.dcp.claim;

import java.net.URI;

/**
 * Value Object: one component an aggregate component is built from (DCP HLD-C2 A.14, {@code child_claims}).
 *
 * <p>The child is a normal component with its own claim; this entry names it by identity only. A required child must be
 * in every composition that selects the aggregate; an optional one may be absent, and then neither it nor its artifact
 * is part of the composition. The children are the aggregate's declared containment.
 *
 * @param claimUri    the child's {@code identity.uri}; unique within the list and never the aggregate's own
 * @param required    whether the aggregate cannot run without the child; always written, so {@code null} is reported
 * @param description the child's role in the aggregate, or {@code null}
 */
public record ChildClaim(URI claimUri, Boolean required, String description) {

    /** @return true when the child must be in every composition that selects the aggregate. */
    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }
}
