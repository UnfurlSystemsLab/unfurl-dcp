package com.unfurl.dcp.claim;

import com.fasterxml.jackson.annotation.JsonAlias;

public record OverlappingConcern(
        String concern,
        @JsonAlias("ownership_position")
        OwnershipPosition ownershipPosition,
        @JsonAlias("resolution_guidance")
        String resolutionGuidance
) {
}
