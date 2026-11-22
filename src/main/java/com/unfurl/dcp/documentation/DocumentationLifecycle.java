package com.unfurl.dcp.documentation;

import java.time.Instant;
import java.util.Objects;

/**
 * Value Object: who produced a projection and when (DCP HLD-C2 G.2 {@code lifecycle}).
 *
 * @param generatedBy the projector's identity, for example its claim URI and version
 * @param generatedAt when the projection was produced
 */
public record DocumentationLifecycle(String generatedBy, Instant generatedAt) {
    /** Validating constructor: both are required. */
    public DocumentationLifecycle {
        Objects.requireNonNull(generatedBy, "generatedBy");
        Objects.requireNonNull(generatedAt, "generatedAt");
    }
}
