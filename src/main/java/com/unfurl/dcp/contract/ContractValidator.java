package com.unfurl.dcp.contract;

import com.unfurl.dcp.trust.TrustCreatedBy;
import com.unfurl.dcp.trust.TrustTier;
import com.unfurl.dcp.trust.TrustTierDeriver;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.SchemaValidationReport;

import java.util.ArrayList;
import java.util.List;

public final class ContractValidator {
    private final TrustTierDeriver trustTierDeriver = new TrustTierDeriver();

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
}
