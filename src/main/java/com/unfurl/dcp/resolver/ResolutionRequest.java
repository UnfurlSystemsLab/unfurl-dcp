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
        Map<String, Object> requiredOfferDetails,
        List<Claim> candidateProviderClaims
) {
    /**
     * Compatibility constructor: preserves existing resolver callers that only
     * constrain need, version, consumer identity, and candidate provider claims.
     */
    public ResolutionRequest(
            String need,
            String requiredKind,
            SemverRange offerVersionRange,
            URI consumerClaimUri,
            List<Claim> candidateProviderClaims
    ) {
        this(need, requiredKind, offerVersionRange, consumerClaimUri, Map.of(), Map.of(), candidateProviderClaims);
    }

    /**
     * Compatibility constructor: preserves existing resolver callers that
     * provide access policies but no required offer-detail subset.
     */
    public ResolutionRequest(
            String need,
            String requiredKind,
            SemverRange offerVersionRange,
            URI consumerClaimUri,
            Map<String, AccessPolicy> accessPoliciesByCapability,
            List<Claim> candidateProviderClaims
    ) {
        this(need, requiredKind, offerVersionRange, consumerClaimUri,
                accessPoliciesByCapability, Map.of(), candidateProviderClaims);
    }

    /**
     * Defensive-copy constructor: normalizes optional policy/detail maps and
     * candidate lists so the resolver can remain branch-light and deterministic.
     */
    public ResolutionRequest {
        accessPoliciesByCapability = accessPoliciesByCapability == null ? Map.of() : Map.copyOf(accessPoliciesByCapability);
        requiredOfferDetails = requiredOfferDetails == null ? Map.of() : Map.copyOf(requiredOfferDetails);
        candidateProviderClaims = candidateProviderClaims == null ? List.of() : List.copyOf(candidateProviderClaims);
    }
}
