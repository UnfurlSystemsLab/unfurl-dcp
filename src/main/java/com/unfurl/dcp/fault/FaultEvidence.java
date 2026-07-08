package com.unfurl.dcp.fault;

import java.util.List;

/**
 * Value object: lists evidence signal classes that can justify a declared
 * runtime fault without binding DCP to any concrete monitoring adapter.
 */
public record FaultEvidence(List<EvidenceSignal> signals) {
    /**
     * Compact constructor: normalizes omitted evidence to an empty immutable
     * list so adapters can still attach evidence references in FaultSignal.
     */
    public FaultEvidence {
        signals = signals == null ? List.of() : List.copyOf(signals);
    }
}
