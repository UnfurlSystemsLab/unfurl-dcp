package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimKey;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.host.VerifiedComposition;
import com.unfurl.dcp.host.VerifiedContract;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.SchemaValidationReport;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validator: the documentation gap gate and the post-projection re-check (DCP HLD-C2 G.1, G.5).
 *
 * <p>{@link #gaps} is static: an entry that is HTTP or hosted, not private, and whose provider offer has no
 * documentation operations is a gap. An {@code external_protocol} offer makes its entry external, so it is never a gap,
 * whatever its deployment kind. Fabric runs it before emitting a visibility policy, and
 * {@link DocumentationProjector#plan} runs it before projecting. A schema is never inferred.
 *
 * <p>{@link #validate} re-checks a produced projection against its inputs, so a projection can be audited independently of
 * the projector that made it. It never scans classpaths, registries, prompts, logs, DTOs, interface-detail hints,
 * environment variables or the network.
 */
public final class CapabilityDocumentationValidator {

    /**
     * Finds every documentation gap.
     *
     * @param composition the verified composition
     * @param claims      provider claims by identity
     * @param bindings    the children's runtime bindings (they decide each entry's surface)
     * @param policy      the visibility policy that would be emitted
     * @return every gap, sorted by contract id; empty when there is none
     */
    public List<DocumentationGap> gaps(VerifiedComposition composition, Map<ClaimKey, Claim> claims,
                                       List<RuntimeBinding> bindings, VisibilityPolicy policy) {
        return gaps(composition.children().all().stream().map(VerifiedContract::contract).toList(), claims, bindings, policy);
    }

    /**
     * Finds every documentation gap over child contracts that are not loaded as a verified set yet: a tool that emits a
     * visibility policy (Fabric's binding generator) has the signed children and the claims they pin, but no runtime
     * bundle. The rule is the same one {@link #gaps(VerifiedComposition, Map, List, VisibilityPolicy)} applies.
     *
     * @param children the composition's child contracts
     * @param claims   provider claims by identity, already checked against the hashes the children pin
     * @param bindings the children's runtime bindings (they decide each entry's surface)
     * @param policy   the visibility policy that would be emitted
     * @return every gap, sorted by contract id; empty when there is none
     */
    public List<DocumentationGap> gaps(List<CompositionContract> children, Map<ClaimKey, Claim> claims,
                                       List<RuntimeBinding> bindings, VisibilityPolicy policy) {
        Map<URI, RuntimeBinding> byContract = new HashMap<>();
        bindings.forEach(binding -> byContract.put(binding.contractId(), binding));
        List<DocumentationGap> gaps = new ArrayList<>();
        for (CompositionContract contract : children) {
            String capability = contract.binding().providerCapability();
            Claim claim = claims.get(new ClaimKey(contract.parties().provider().claimUri(), contract.parties().provider().claimVersion()));
            Offer offer = claim == null ? null : Entries.offer(claim, capability).orElse(null);
            EntrySurface surface = Entries.surfaceOf(contract, byContract.get(contract.contractId()), offer);
            if (!Entries.mustBeDocumented(surface, policy.ofCapability(capability))) continue;
            boolean documented = offer != null && Entries.documented(offer);
            if (!documented) {
                gaps.add(new DocumentationGap(contract.contractId(), capability, contract.parties().provider().claimUri()));
            }
        }
        gaps.sort(Comparator.comparing(gap -> gap.contractId().toString()));
        return List.copyOf(gaps);
    }

    /**
     * Re-checks a projection against its inputs: every child has exactly one report entry; every documented capability
     * is a child of the verified root, is reported ACTIVE, has an enabled binding (outside a host view) and pinned
     * operations matching its claim; and nothing carries a {@code secret://} reference.
     */
    public SchemaValidationReport validate(CapabilityDocumentation documentation, DocumentationInputs inputs) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<URI> children = new HashSet<>();
        inputs.composition().children().all().forEach(child -> children.add(child.contract().contractId()));
        Map<URI, Long> reported = new HashMap<>();
        documentation.report().entries().forEach(entry -> reported.merge(entry.contractId(), 1L, Long::sum));
        for (URI child : children) {
            if (reported.getOrDefault(child, 0L) != 1L) {
                diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "contract " + child
                        + " must have exactly one report entry", "report.entries"));
            }
        }
        reported.keySet().stream().filter(id -> !children.contains(id)).forEach(id -> diagnostics.add(Diagnostic.error(
                ErrorCode.VALIDATION_FAILED, "report entry " + id + " is not a child of the verified root", "report.entries")));
        Map<URI, ReportEntry> entries = new HashMap<>();
        documentation.report().entries().forEach(entry -> entries.put(entry.contractId(), entry));
        Map<URI, RuntimeBinding> bindings = new HashMap<>();
        inputs.runtimeBindings().forEach(binding -> bindings.put(binding.contractId(), binding));
        for (DocumentedComponent component : documentation.components()) {
            for (DocumentedCapability capability : component.capabilities()) {
                String at = "components[" + component.componentUri() + "].capabilities[" + capability.capability() + "]";
                ReportEntry entry = entries.get(capability.contractId());
                if (entry == null || entry.reason() != ReasonCode.ACTIVE) {
                    diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "a documented capability must be reported ACTIVE", at));
                }
                RuntimeBinding binding = bindings.get(capability.contractId());
                if (binding == null || binding.runtimePolicy() == null || !binding.runtimePolicy().enabled()) {
                    diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "a documented capability needs an enabled binding", at));
                }
                Claim claim = inputs.providerClaims().get(new ClaimKey(capability.providerComponentUri(), capability.providerComponentVersion()));
                List<DocumentedOperation> expected = claim == null ? List.of() : Entries.offer(claim, capability.capability())
                        .filter(Entries::documented)
                        .map(offer -> offer.documentation().operations().stream().map(DocumentedOperation::of)
                                .sorted(Comparator.comparing(DocumentedOperation::operation)).toList())
                        .orElse(List.of());
                if (!expected.equals(capability.operations())) {
                    diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED,
                            "documented operations must be exactly the provider claim's pinned operations", at + ".operations"));
                }
            }
        }
        if (documentation.toString().contains("secret://")) {
            diagnostics.add(Diagnostic.error(ErrorCode.BINDING_INLINE_SECRET, "documentation must not carry secret references", "$"));
        }
        return new SchemaValidationReport(diagnostics);
    }

    /** Helper used by the projectors: the offer a contract binds, or a refusal naming the missing offer. */
    static Offer requireOffer(Claim claim, CompositionContract contract) {
        String capability = contract.binding().providerCapability();
        return Entries.offer(claim, capability).orElseThrow(() -> new DocumentationException(DocumentationException.CLAIM_OFFER_MISSING,
                "the claim " + claim.identity().uri() + "@" + claim.identity().version() + " offers no " + capability
                        + " but contract " + contract.contractId() + " binds it"));
    }
}
