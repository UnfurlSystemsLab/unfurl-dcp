package com.unfurl.dcp.claim;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Schema record: names a concern that overlaps another component boundary and records the provider's
 * ownership position plus guidance. The fields feed conflict-resolution diagnostics and negotiation
 * prompts rather than runtime broker decisions.
 */
public record OverlappingConcern(
        String concern,
        @JsonAlias("ownership_position")
        OwnershipPosition ownershipPosition,
        @JsonAlias("resolution_guidance")
        String resolutionGuidance
) {
}
