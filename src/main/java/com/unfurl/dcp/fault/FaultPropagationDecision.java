package com.unfurl.dcp.fault;

import java.util.List;

/**
 * Result DTO: deterministic output of the DCP fault propagation gate for one
 * runtime signal evaluated against one source claim.
 */
public record FaultPropagationDecision(
        boolean propagates,
        ParentImpact parentImpact,
        String reason,
        List<String> affectedNeeds,
        List<String> affectedOffers,
        List<String> affectedConstraints
) {
    /**
     * Compact constructor: freezes affected-surface lists and defaults omitted
     * reason text for stable API responses.
     */
    public FaultPropagationDecision {
        parentImpact = parentImpact == null ? ParentImpact.NONE : parentImpact;
        reason = reason == null ? "" : reason;
        affectedNeeds = affectedNeeds == null ? List.of() : List.copyOf(affectedNeeds);
        affectedOffers = affectedOffers == null ? List.of() : List.copyOf(affectedOffers);
        affectedConstraints = affectedConstraints == null ? List.of() : List.copyOf(affectedConstraints);
    }
}
