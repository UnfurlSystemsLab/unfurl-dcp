package com.unfurl.dcp.fault;

/**
 * Value object: declares the deterministic parent-impact gate for one emitted
 * DCP fault.
 */
public record FaultPropagation(
        ParentImpact parentImpact,
        String propagatesWhen,
        String suppressesWhen
) {
    /**
     * Compact constructor: defaults omitted impact to NONE and trims textual
     * gate conditions without interpreting them at runtime.
     */
    public FaultPropagation {
        parentImpact = parentImpact == null ? ParentImpact.NONE : parentImpact;
        propagatesWhen = propagatesWhen == null ? "" : propagatesWhen.trim();
        suppressesWhen = suppressesWhen == null ? "" : suppressesWhen.trim();
    }
}
