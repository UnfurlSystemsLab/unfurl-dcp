package com.unfurl.dcp.resolver;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.versioning.SemverRange;

import java.net.URI;
import java.util.List;
import java.util.Map;

public record ResolutionRequest(
        String need,
        String requiredKind,
        SemverRange offerVersionRange,
        URI consumerClaimUri,
        Map<String, AccessPolicy> accessPoliciesByCapability,
        List<Claim> candidateProviderClaims
) {
    public ResolutionRequest(
            String need,
            String requiredKind,
            SemverRange offerVersionRange,
            URI consumerClaimUri,
            List<Claim> candidateProviderClaims
    ) {
        this(need, requiredKind, offerVersionRange, consumerClaimUri, Map.of(), candidateProviderClaims);
    }

    public ResolutionRequest {
        accessPoliciesByCapability = accessPoliciesByCapability == null ? Map.of() : Map.copyOf(accessPoliciesByCapability);
        candidateProviderClaims = candidateProviderClaims == null ? List.of() : List.copyOf(candidateProviderClaims);
    }
}
