package com.unfurl.dcp.contract;

import java.util.List;

public record Invalidation(List<InvalidationTrigger> triggers, RuntimeViolationPolicy onRuntimeViolation) {
    public Invalidation {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
    }
}
