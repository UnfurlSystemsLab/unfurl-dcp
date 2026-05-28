package com.unfurl.dcp.resolver;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.versioning.SemverRange;

import java.net.URI;
import java.util.List;

public record ResolutionRequest(
        String need,
        String requiredKind,
        SemverRange offerVersionRange,
        URI consumerClaimUri,
        List<Claim> candidateProviderClaims
) {
    public ResolutionRequest {
        candidateProviderClaims = candidateProviderClaims == null ? List.of() : List.copyOf(candidateProviderClaims);
    }
}
