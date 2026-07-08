package com.unfurl.dcp.fault;

import java.util.List;

/**
 * Value object: declares which DCP surfaces a fault can affect. At least one
 * list must be non-empty for a valid fault declaration.
 */
public record FaultAffects(
        List<String> needs,
        List<String> offers,
        List<String> constraints
) {
    /**
     * Compact constructor: freezes list fields so fault declarations remain
     * immutable after admission or deserialization.
     */
    public FaultAffects {
        needs = needs == null ? List.of() : List.copyOf(needs);
        offers = offers == null ? List.of() : List.copyOf(offers);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }

    /**
     * Factory: returns the empty affected-surface set for malformed-input tests
     * and optional runtime signals.
     */
    public static FaultAffects empty() {
        return new FaultAffects(List.of(), List.of(), List.of());
    }

    /**
     * Predicate: true when no DCP need, offer, or constraint is declared as
     * affected by the fault.
     */
    public boolean emptyAffectedSurface() {
        return needs.isEmpty() && offers.isEmpty() && constraints.isEmpty();
    }
}
