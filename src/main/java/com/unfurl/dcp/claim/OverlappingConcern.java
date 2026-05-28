package com.unfurl.dcp.claim;

public record OverlappingConcern(
        String concern,
        OwnershipPosition ownershipPosition,
        String resolutionGuidance
) {
}
