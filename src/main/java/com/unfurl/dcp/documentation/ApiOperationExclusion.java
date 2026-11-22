package com.unfurl.dcp.documentation;

import java.net.URI;
import java.util.Objects;

/**
 * Value Object: one product API operation that the projection did not render, and why (DCP HLD-C2 G.4).
 *
 * @param componentUri the component whose document declares it
 * @param apiId        the document
 * @param operation    {@code <METHOD> <path>}
 * @param reason       {@code SURFACE_NOT_SERVED} or {@code POLICY_PRIVATE}
 */
public record ApiOperationExclusion(URI componentUri, String apiId, String operation, ReasonCode reason) {
    /** Validating constructor: only the two operation reasons are allowed. */
    public ApiOperationExclusion {
        Objects.requireNonNull(componentUri, "componentUri");
        Objects.requireNonNull(apiId, "apiId");
        Objects.requireNonNull(operation, "operation");
        if (reason != ReasonCode.SURFACE_NOT_SERVED && reason != ReasonCode.POLICY_PRIVATE) {
            throw new IllegalArgumentException("an API operation is excluded only as SURFACE_NOT_SERVED or POLICY_PRIVATE");
        }
    }
}
