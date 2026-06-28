package com.unfurl.dcp.resolver;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.versioning.SemverHelpers;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class CapabilityResolver {
    private final SemverHelpers semver = new SemverHelpers();

    public ResolutionResult resolve(ResolutionRequest request) {
        if (request == null) {
            return ResolutionResult.unresolved(ErrorCode.RESOLUTION_FAILED.name());
        }
        String range = request.offerVersionRange() == null ? "*" : request.offerVersionRange().expression();
        List<Candidate> candidates = request.candidateProviderClaims().stream()
                .flatMap(claim -> claim.offers().stream().map(offer -> new Candidate(claim, offer)))
                .filter(candidate -> Objects.equals(candidate.offer.capability(), request.need()))
                .filter(candidate -> semver.satisfies(candidate.offer.version(), range))
                .filter(candidate -> accessPolicy(candidate.offer, request).allows(request.consumerClaimUri()))
                .toList();
        if (candidates.isEmpty()) {
            return ResolutionResult.unresolved(ErrorCode.NO_MATCHING_CONTRACT.name());
        }
        Candidate highest = candidates.stream()
                .max(Comparator.comparing(candidate -> candidate.offer.version(), semver.semverComparator()))
                .orElseThrow();
        long highestMatches = candidates.stream()
                .filter(candidate -> Objects.equals(candidate.offer.version(), highest.offer.version()))
                .count();
        if (highestMatches > 1) {
            return ResolutionResult.unresolved(ErrorCode.MULTIPLE_MATCHES.name());
        }
        return new ResolutionResult(
                true,
                highest.claim.identity().uri(),
                highest.offer.capability(),
                highest.offer.version(),
                "MATCH_FOUND");
    }

    private AccessPolicy accessPolicy(Offer offer, ResolutionRequest request) {
        return request.accessPoliciesByCapability().getOrDefault(offer.capability(), new AccessPolicy(offer.consumerAccess(), java.util.Set.of()));
    }

    private record Candidate(Claim claim, Offer offer) {
    }
}
