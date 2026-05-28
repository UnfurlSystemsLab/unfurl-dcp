package com.unfurl.dcp.claim;

import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.SchemaValidationReport;
import com.unfurl.dcp.versioning.SemverHelpers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class ClaimValidator {
    private final SemverHelpers semver = new SemverHelpers();

    public SchemaValidationReport validate(Claim claim) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (claim == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "claim is required", "$"));
            return new SchemaValidationReport(diagnostics);
        }
        if (claim.identity() == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "identity is required", "identity"));
        }
        if (claim.domain() == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "domain is required", "domain"));
        } else {
            if (claim.domain().boundaryPrinciples().isEmpty()) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "boundary principles are required", "domain.boundary_principles"));
            }
            HashSet<String> concerns = new HashSet<>();
            for (Concern concern : claim.domain().concerns()) {
                if (concern.concern() != null && !concerns.add(concern.concern())) {
                    diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "concern identifiers must be unique", "domain.concerns"));
                }
            }
        }
        if (claim.refusals().isEmpty()) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "refusals are required", "refusals"));
        }
        if (claim.identity() != null
                && claim.identity().kind() == ComponentKind.INTELLIGENT_COMPONENT
                && claim.negotiationSurface() == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "intelligent components require a negotiation surface", "negotiation_surface"));
        }
        if (claim.metadata() == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "metadata is required", "metadata"));
        } else {
            if (claim.metadata().dcpVersion() == null || claim.metadata().dcpVersion().isBlank()) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "metadata.dcp_version is required", "metadata.dcp_version"));
            } else if (!semver.satisfies(claim.metadata().dcpVersion(), ">=0.2.0")) {
                diagnostics.add(Diagnostic.error(ErrorCode.DCP_VERSION_UNSUPPORTED, "metadata.dcp_version must be >= 0.2.0", "metadata.dcp_version"));
            }
            if (claim.identity() != null && !Objects.equals(claim.identity().version(), claim.metadata().claimVersion())) {
                diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "metadata.claim_version must match identity.version", "metadata.claim_version"));
            }
        }
        for (Offer offer : claim.offers()) {
            validateOffer(offer, diagnostics);
        }
        for (Refusal refusal : claim.refusals()) {
            if ("everything-else".equalsIgnoreCase(refusal.concern()) || refusal.rationale() == null || refusal.rationale().length() < 12) {
                diagnostics.add(Diagnostic.warning(ErrorCode.CLAIM_MALFORMED, "refusal specificity is weak", "refusals"));
            }
        }
        return new SchemaValidationReport(diagnostics);
    }

    private void validateOffer(Offer offer, List<Diagnostic> diagnostics) {
        if (offer == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "offer is required", "offers"));
            return;
        }
        if (!semver.satisfies(offer.version(), "*")) {
            diagnostics.add(Diagnostic.error(ErrorCode.OFFER_VERSION_UNSATISFIED, "offer version must be SemVer", "offers.version"));
        }
        boolean negotiation = offer.offerInterface() != null && offer.offerInterface().interfaceKind() == InterfaceKind.NEGOTIATION;
        if ((offer.metered() || negotiation) && (offer.costImplications() == null || offer.costImplications().isBlank())) {
            diagnostics.add(Diagnostic.error(ErrorCode.CLAIM_MALFORMED, "cost_implications are required for metered or negotiation offers", "offers.cost_implications"));
        }
    }
}
