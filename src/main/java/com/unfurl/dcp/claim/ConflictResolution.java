package com.unfurl.dcp.claim;

import java.util.List;

public record ConflictResolution(
        List<OverlappingConcern> overlappingConcerns,
        List<String> precedenceRules,
        boolean requiresHumanEscalation
) {
    public ConflictResolution {
        overlappingConcerns = overlappingConcerns == null ? List.of() : List.copyOf(overlappingConcerns);
        precedenceRules = precedenceRules == null ? List.of() : List.copyOf(precedenceRules);
    }
}
