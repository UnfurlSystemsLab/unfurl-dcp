package com.unfurl.dcp.resolver;

import java.net.URI;

public record ResolutionResult(
        boolean resolved,
        URI providerClaimUri,
        String providerCapability,
        String resolvedOfferVersion,
        String reason
) {
    public static ResolutionResult unresolved(String reason) {
        return new ResolutionResult(false, null, null, null, reason);
    }
}
