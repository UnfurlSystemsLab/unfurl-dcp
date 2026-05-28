package com.unfurl.dcp.resolver;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.versioning.SemverHelpers;

import java.util.Objects;

public final class CapabilityResolver {
    private final SemverHelpers semver = new SemverHelpers();

    public ResolutionResult resolve(ResolutionRequest request) {
        if (request == null) {
            return ResolutionResult.unresolved(ErrorCode.RESOLUTION_FAILED.name());
        }
        String range = request.offerVersionRange() == null ? "*" : request.offerVersionRange().expression();
        return request.candidateProviderClaims().stream()
                .flatMap(claim -> claim.offers().stream().map(offer -> new Candidate(claim, offer)))
                .filter(candidate -> Objects.equals(candidate.offer.capability(), request.need()))
                .filter(candidate -> semver.satisfies(candidate.offer.version(), range))
                .filter(candidate -> accessPolicy(candidate.offer, request).allows(request.consumerClaimUri()))
                .max((left, right) -> semver.semverComparator().compare(left.offer.version(), right.offer.version()))
                .map(candidate -> new ResolutionResult(
                        true,
                        candidate.claim.identity().uri(),
                        candidate.offer.capability(),
                        candidate.offer.version(),
                        "MATCH_FOUND"))
                .orElseGet(() -> ResolutionResult.unresolved(ErrorCode.NO_MATCHING_CONTRACT.name()));
    }

    private AccessPolicy accessPolicy(Offer offer, ResolutionRequest request) {
        return request.accessPoliciesByCapability().getOrDefault(offer.capability(), new AccessPolicy(offer.consumerAccess(), java.util.Set.of()));
    }

    private record Candidate(Claim claim, Offer offer) {
    }
}
