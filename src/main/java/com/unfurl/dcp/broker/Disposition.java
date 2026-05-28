package com.unfurl.dcp.broker;

import java.net.URI;

public record Disposition(
        DispositionKind kind,
        URI matchedContractId,
        String matchedContractVersion,
        String redirection,
        String rationale,
        DispositionReason reasonCode
) {
    public static Disposition accept(URI contractId, String contractVersion) {
        return new Disposition(DispositionKind.ACCEPT, contractId, contractVersion, null, "matching frozen contract found", DispositionReason.MATCH_FOUND);
    }

    public static Disposition refuse(DispositionReason reason, String rationale, String redirection) {
        return new Disposition(DispositionKind.REFUSE, null, null, redirection, rationale, reason);
    }
}
