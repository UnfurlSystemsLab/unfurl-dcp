package com.unfurl.dcp.contract;

import com.unfurl.dcp.trust.TrustCreatedBy;
import com.unfurl.dcp.trust.TrustTier;
import com.unfurl.dcp.trust.TrustTierDeriver;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.SchemaValidationReport;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validator for DCP composition contracts and aggregate contract trees.
 *
 * <p>Pattern: Composite validator. Leaf validation enforces contract invariants; tree validation walks
 * recursive containment refs so aggregate contracts cannot omit child DCP contracts or contain cycles.
 */
public final class ContractValidator {
    private final TrustTierDeriver trustTierDeriver = new TrustTierDeriver();

    /**
     * Validate a single composition contract without following child refs.
     *
     * @param contract contract to validate.
     * @return validation report with deterministic diagnostics.
     */
    public SchemaValidationReport validate(CompositionContract contract) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (contract == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "contract is required", "$"));
            return new SchemaValidationReport(diagnostics);
        }
        if (contract.parties() == null || contract.parties().consumer() == null || contract.parties().provider() == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CONTRACT_PARTIES_INVALID, "consumer and provider parties are required", "parties"));
        }
        if (contract.binding() == null || contract.binding().providerCapability() == null || contract.binding().providerCapabilityVersion() == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "binding provider capability and version are required", "binding"));
        }
        validateProvenance(contract, diagnostics);
        validateTrust(contract, diagnostics);
        if (contract.invalidation() != null && contract.invalidation().onRuntimeViolation() != RuntimeViolationPolicy.HARD_FAIL) {
            diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "runtime violation policy must be HARD_FAIL", "invalidation.on_runtime_violation"));
        }
        return new SchemaValidationReport(diagnostics);
    }

    /**
     * Composite validator: validates an aggregate contract tree by walking metadata containment
     * refs and applying normal contract validation to every loaded child. Missing children and
     * cycles are hard errors because runtime binding generation must not silently discard a
     * governed contract edge.
     */
    public SchemaValidationReport validateTree(
            CompositionContract root,
            Map<URI, CompositionContract> contractsById
    ) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (root == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "contract is required", "$"));
            return new SchemaValidationReport(diagnostics);
        }
        walk(root, contractsById == null ? Map.of() : contractsById,
                new ArrayDeque<>(), new LinkedHashSet<>(), diagnostics);
        return new SchemaValidationReport(diagnostics);
    }

    /**
     * Provenance rule validator: checks the negotiation mode fields required by the DCP schema.
     */
    private void validateProvenance(CompositionContract contract, List<Diagnostic> diagnostics) {
        Provenance provenance = contract.provenance();
        if (provenance == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CONTRACT_PROVENANCE_INCONSISTENT, "provenance is required", "provenance"));
            return;
        }
        if (provenance.mode() == NegotiationMode.C2C && (provenance.modelId() == null || provenance.modelId().isBlank())) {
            diagnostics.add(Diagnostic.error(ErrorCode.CONTRACT_PROVENANCE_INCONSISTENT, "C2C provenance requires model_id", "provenance.model_id"));
        }
        if (provenance.mode() == NegotiationMode.H2C && !provenance.humanInLoop()) {
            diagnostics.add(Diagnostic.error(ErrorCode.CONTRACT_PROVENANCE_INCONSISTENT, "H2C provenance requires human_in_loop", "provenance.human_in_loop"));
        }
    }

    /**
     * Trust rule validator: derives the expected tier from provenance and rejects mismatches.
     */
    private void validateTrust(CompositionContract contract, List<Diagnostic> diagnostics) {
        if (contract.trust() == null || contract.trust().tier() == null || contract.provenance() == null || contract.provenance().createdBy() == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.CONTRACT_TRUST_INCONSISTENT, "trust tier and provenance.created_by are required", "trust"));
            return;
        }
        TrustCreatedBy createdBy = contract.provenance().createdBy() == CreatedBy.EMBEDDED_SELF
                ? TrustCreatedBy.EMBEDDED_SELF
                : TrustCreatedBy.FABRIC;
        TrustTier expected = trustTierDeriver.derive(createdBy);
        if (contract.trust().tier() != expected) {
            diagnostics.add(Diagnostic.error(ErrorCode.CONTRACT_TRUST_INCONSISTENT, "trust tier does not match provenance.created_by", "trust.tier"));
        }
    }

    /**
     * Recursive composite walker: follows deterministic child refs from
     * {@link CompositionContractMetadata} and reports the first cycle edge encountered.
     */
    private void walk(
            CompositionContract contract,
            Map<URI, CompositionContract> contractsById,
            ArrayDeque<URI> path,
            Set<URI> visited,
            List<Diagnostic> diagnostics
    ) {
        URI contractId = contract.contractId();
        if (contractId == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "contract_id is required", "contract_id"));
            return;
        }
        if (path.contains(contractId)) {
            diagnostics.add(Diagnostic.error(ErrorCode.CONTRACT_CONTAINMENT_CYCLE,
                    "contract containment cycle at " + contractId,
                    "metadata.extensions"));
            return;
        }
        if (!visited.add(contractId)) {
            return;
        }

        diagnostics.addAll(validate(contract).diagnostics());
        path.addLast(contractId);
        for (URI childId : childContractIds(contract)) {
            CompositionContract child = contractsById.get(childId);
            if (child == null) {
                diagnostics.add(Diagnostic.error(ErrorCode.CONTRACT_CHILD_MISSING,
                        "contained contract is not loaded: " + childId,
                        "metadata.extensions"));
                continue;
            }
            walk(child, contractsById, path, visited, diagnostics);
        }
        path.removeLast();
    }

    /**
     * Child-reference accessor: treats missing metadata as a leaf contract.
     */
    private List<URI> childContractIds(CompositionContract contract) {
        return contract.metadata() == null ? List.of() : contract.metadata().childContractIds();
    }
}
