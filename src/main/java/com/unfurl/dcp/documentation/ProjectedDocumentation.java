package com.unfurl.dcp.documentation;

import java.util.Objects;

/**
 * Value Object: a projection together with the verified bytes its renderers need, so a caller can render it without
 * I/O and without the projector keeping state.
 *
 * @param documentation the projection
 * @param artifacts     the verified bytes of every artifact it references
 */
public record ProjectedDocumentation(CapabilityDocumentation documentation, DocumentationArtifactStore artifacts) {
    /** Validating constructor: both are required. */
    public ProjectedDocumentation {
        Objects.requireNonNull(documentation, "documentation");
        Objects.requireNonNull(artifacts, "artifacts");
    }
}
