package com.unfurl.dcp.runtimebinding;

import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.SchemaValidationReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RuntimeBindingValidator {
    public SchemaValidationReport validate(RuntimeBinding binding, CompositionContract contract) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (binding == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "runtime binding is required", "$"));
            return new SchemaValidationReport(diagnostics);
        }
        if (contract != null) {
            if (!Objects.equals(binding.contractId(), contract.contractId())) {
                diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "contract_id must match contract", "contract_id"));
            }
            if (!Objects.equals(binding.contractVersion(), contract.contractVersion())) {
                diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "contract_version must match contract", "contract_version"));
            }
        }
        if (binding.providerInstance() != null && binding.providerInstance().baseUrl() != null && binding.providerInstance().baseUrlRef() != null) {
            diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "base_url and base_url_ref are mutually exclusive", "provider_instance"));
        }
        return new SchemaValidationReport(diagnostics);
    }
}
