package com.unfurl.dcp.fault;

import java.util.List;

/**
 * Schema record: top-level claim section containing the declared fault
 * vocabulary emitted by a component.
 */
public record FaultPolicy(List<FaultDeclaration> emitted) {
    /**
     * Compact constructor: freezes emitted declarations; callers must still
     * provide this top-level policy explicitly in every claim.
     */
    public FaultPolicy {
        emitted = emitted == null ? List.of() : List.copyOf(emitted);
    }

    /**
     * Factory: returns the explicit empty policy for components that declare no
     * operational faults.
     */
    public static FaultPolicy empty() {
        return new FaultPolicy(List.of());
    }
}
