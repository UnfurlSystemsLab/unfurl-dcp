package com.unfurl.dcp.fault;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Runtime DTO: an observed occurrence of a declared DCP fault, emitted by a
 * host or adapter and evaluated against the source claim by the propagation gate.
 */
public record FaultSignal(
        String faultId,
        URI sourceClaimUri,
        String sourceInstance,
        URI contractId,
        URI bindingId,
        String capability,
        String code,
        FaultCategory category,
        FaultSeverity severity,
        Instant observedAt,
        List<String> affectedNeeds,
        List<String> affectedOffers,
        List<String> affectedConstraints,
        List<String> evidenceRefs,
        String correlationId
) {
    /**
     * Compact constructor: normalizes optional strings and freezes collections
     * while preserving the fault code for deterministic declaration lookup.
     */
    public FaultSignal {
        faultId = faultId == null ? "" : faultId.trim();
        sourceInstance = sourceInstance == null ? "" : sourceInstance.trim();
        capability = capability == null ? "" : capability.trim();
        code = code == null ? "" : code.trim();
        affectedNeeds = affectedNeeds == null ? List.of() : List.copyOf(affectedNeeds);
        affectedOffers = affectedOffers == null ? List.of() : List.copyOf(affectedOffers);
        affectedConstraints = affectedConstraints == null ? List.of() : List.copyOf(affectedConstraints);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        correlationId = correlationId == null ? "" : correlationId.trim();
    }
}
