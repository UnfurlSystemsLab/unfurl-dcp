package com.unfurl.dcp.fault;

import java.util.List;

/**
 * Schema record: top-level claim section containing the declared fault
 * vocabulary emitted by a component.
 */
public record FaultPolicy(List<FaultDeclaration> emitted) {
    /**
     * Compact constructor: freezes emitted declarations and treats a missing
     * policy as an empty policy for internal construction.
     */
    public FaultPolicy {
        emitted = emitted == null ? List.of() : List.copyOf(emitted);
    }

    /**
     * Factory: returns the product-development default for components that do
     * not emit declared operational faults yet.
     */
    public static FaultPolicy empty() {
        return new FaultPolicy(List.of());
    }
}
