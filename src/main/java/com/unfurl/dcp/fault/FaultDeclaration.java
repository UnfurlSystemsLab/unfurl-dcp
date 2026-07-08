package com.unfurl.dcp.fault;

/**
 * Schema record: one DCP fault code a component may emit, including affected
 * DCP surfaces, evidence classes, parent propagation, and allowed remediation.
 */
public record FaultDeclaration(
        String code,
        FaultCategory category,
        FaultSeverity severity,
        String description,
        FaultAffects affects,
        FaultEvidence evidence,
        FaultPropagation propagation,
        FaultRemediation remediation
) {
    /**
     * Compact constructor: applies safe nested defaults while preserving blank
     * required fields for ClaimValidator to report as structured diagnostics.
     */
    public FaultDeclaration {
        code = code == null ? "" : code.trim();
        description = description == null ? "" : description.trim();
        affects = affects == null ? FaultAffects.empty() : affects;
        evidence = evidence == null ? new FaultEvidence(java.util.List.of()) : evidence;
        propagation = propagation == null ? new FaultPropagation(ParentImpact.NONE, "", "") : propagation;
        remediation = remediation == null ? new FaultRemediation(java.util.List.of()) : remediation;
    }
}
