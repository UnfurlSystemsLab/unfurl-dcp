package com.unfurl.dcp.validation;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.manifest.WebappManifest;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.versioning.SemverHelpers;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cross-schema validator service: checks invariants that no single DCP schema can know alone.
 * It binds claim/manifest identity, contract party pins, provider capability versions, and runtime
 * binding identity back to the frozen contract while returning structured diagnostics instead of
 * throwing for expected validation failures.
 */
public final class CrossSchemaValidator {
    private final SemverHelpers semver = new SemverHelpers();

    /**
     * Validate that a webapp manifest is the frontend projection of the supplied claim. The output
     * reports URI/version drift so hosts do not mount a UI facet for the wrong component identity.
     */
    public SchemaValidationReport validate(Claim claim, WebappManifest manifest) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (claim == null || manifest == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.MANIFEST_MISMATCH, "claim and manifest are required", "$"));
            return new SchemaValidationReport(diagnostics);
        }
        if (!Objects.equals(claim.identity().uri(), manifest.componentUri())) {
            diagnostics.add(Diagnostic.error(ErrorCode.MANIFEST_MISMATCH, "manifest component_uri must match claim", "component_uri"));
        }
        if (!Objects.equals(claim.identity().version(), manifest.componentVersion())) {
            diagnostics.add(Diagnostic.error(ErrorCode.MANIFEST_MISMATCH, "manifest component_version must match claim", "component_version"));
        }
        return new SchemaValidationReport(diagnostics);
    }

    /**
     * Validate that a composition contract still points at pinned consumer/provider claims and that
     * the provider exposes the bound capability at a satisfying offer version. Newer supplied claim
     * versions are warnings because they indicate invalidation/drift rather than malformed history.
     */
    public SchemaValidationReport validate(CompositionContract contract, Map<URI, Claim> claimsByUri) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (contract == null || claimsByUri == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "contract and claims are required", "$"));
            return new SchemaValidationReport(diagnostics);
        }
        validateParty("consumer", contract.parties().consumer().claimUri(), contract.parties().consumer().claimVersion(), claimsByUri, diagnostics);
        Claim provider = validateParty("provider", contract.parties().provider().claimUri(), contract.parties().provider().claimVersion(), claimsByUri, diagnostics);
        if (provider != null) {
            boolean capabilityFound = provider.offers().stream()
                    .filter(offer -> Objects.equals(offer.capability(), contract.binding().providerCapability()))
                    .map(Offer::version)
                    .anyMatch(version -> semver.satisfies(version, contract.binding().providerCapabilityVersion()));
            if (!capabilityFound) {
                diagnostics.add(Diagnostic.error(ErrorCode.OFFER_VERSION_UNSATISFIED, "provider capability/version not found", "binding.provider_capability"));
            }
        }
        return new SchemaValidationReport(diagnostics);
    }

    /**
     * Party pin helper: resolves one contract party against the supplied claim map and emits either
     * missing/mismatched errors or a version-drift warning for newer claims. Returning the claim lets
     * the provider path continue into capability-version checks.
     */
    private Claim validateParty(
            String partyName,
            URI expectedClaimUri,
            String pinnedClaimVersion,
            Map<URI, Claim> claimsByUri,
            List<Diagnostic> diagnostics
    ) {
        Claim claim = claimsByUri.get(expectedClaimUri);
        String fieldPath = "parties." + partyName;
        if (claim == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CONTRACT_PARTIES_INVALID, partyName + " claim not found", fieldPath));
            return null;
        }
        if (!Objects.equals(claim.identity().version(), pinnedClaimVersion)) {
            if (semver.satisfies(claim.identity().version(), ">" + pinnedClaimVersion)) {
                diagnostics.add(new Diagnostic(
                        Severity.WARNING,
                        ErrorCode.CONTRACT_INVALIDATED,
                        partyName + " claim version drifted from pinned contract version",
                        claim.identity().uri(),
                        null,
                        null,
                        null,
                        fieldPath + ".claim_version",
                        pinnedClaimVersion,
                        null,
                        null,
                        Map.of("pinned_version", pinnedClaimVersion, "supplied_version", claim.identity().version())));
            } else {
                diagnostics.add(Diagnostic.error(ErrorCode.CONTRACT_PARTIES_INVALID, partyName + " claim version does not match contract pin", fieldPath + ".claim_version"));
            }
        }
        return claim;
    }

    /**
     * Validate that a runtime binding targets the exact frozen contract id and version. More detailed
     * runtime-policy and secret checks remain in RuntimeBindingValidator to keep layering clear.
     */
    public SchemaValidationReport validate(RuntimeBinding binding, CompositionContract contract) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (binding == null || contract == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "runtime binding and contract are required", "$"));
        } else if (!Objects.equals(binding.contractId(), contract.contractId()) || !Objects.equals(binding.contractVersion(), contract.contractVersion())) {
            diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "runtime binding must target contract id/version", "contract_id"));
        }
        return new SchemaValidationReport(diagnostics);
    }
}
