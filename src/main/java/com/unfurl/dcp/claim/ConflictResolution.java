package com.unfurl.dcp.claim;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * Schema record: captures how a claim positions itself when concerns overlap with another component.
 * Lists are defensively copied so validators and projectors see stable conflict data while the
 * requires-human-escalation flag remains the explicit governance signal.
 */
public record ConflictResolution(
        List<OverlappingConcern> overlappingConcerns,
        List<String> precedenceRules,
        @JsonAlias("requires_human_escalation")
        boolean requiresHumanEscalation
) {
    /**
     * Defensive-copy constructor: treats omitted collections as empty and prevents caller mutation
     * from changing conflict-resolution diagnostics after validation.
     */
    public ConflictResolution {
        overlappingConcerns = overlappingConcerns == null ? List.of() : List.copyOf(overlappingConcerns);
        precedenceRules = precedenceRules == null ? List.of() : List.copyOf(precedenceRules);
    }
}
