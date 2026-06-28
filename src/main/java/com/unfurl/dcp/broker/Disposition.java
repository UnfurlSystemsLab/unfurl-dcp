package com.unfurl.dcp.broker;

import java.net.URI;

/**
 * Result record: captures the broker's deterministic accept/refuse decision. Accepted dispositions
 * carry the frozen contract id/version that accept must re-fetch; refusals carry a reason and optional
 * precomputed redirection without embedding full claims or contracts.
 */
public record Disposition(
        DispositionKind kind,
        URI matchedContractId,
        String matchedContractVersion,
        String redirection,
        String rationale,
        DispositionReason reasonCode
) {
    /**
     * Factory for accepted matches: records the exact frozen contract identity that must be verified
     * again before capability registration.
     */
    public static Disposition accept(URI contractId, String contractVersion) {
        return new Disposition(DispositionKind.ACCEPT, contractId, contractVersion, null, "matching frozen contract found", DispositionReason.MATCH_FOUND);
    }

    /**
     * Factory for structured refusals: keeps human rationale and machine reason paired while leaving
     * contract identity empty by invariant.
     */
    public static Disposition refuse(DispositionReason reason, String rationale, String redirection) {
        return new Disposition(DispositionKind.REFUSE, null, null, redirection, rationale, reason);
    }
}
