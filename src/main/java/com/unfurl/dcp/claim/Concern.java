package com.unfurl.dcp.claim;

import java.util.List;

public record Concern(
        String concern,
        String description,
        String scopeNotes,
        List<StateOwned> ownsState,
        List<DecisionOwned> ownsDecisions
) {
    public Concern {
        ownsState = ownsState == null ? List.of() : List.copyOf(ownsState);
        ownsDecisions = ownsDecisions == null ? List.of() : List.copyOf(ownsDecisions);
    }
}
