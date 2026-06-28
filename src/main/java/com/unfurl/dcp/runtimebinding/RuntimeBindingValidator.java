package com.unfurl.dcp.runtimebinding;

import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.SchemaValidationReport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validator strategy: enforces the runtime-binding firewall between frozen contract semantics and
 * deployment-time knobs. It accepts only reference-style secrets/configuration, checks binding
 * identity pins against the contract, and rejects fields that attempt to alter ownership, trust,
 * conflict, dependency, or invalidation decisions made at design time.
 */
public final class RuntimeBindingValidator {
    private static final Pattern SECRET_KEY = Pattern.compile(".*(secret|password|passwd|token|api[_-]?key|credential|private[_-]?key).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_VALUE = Pattern.compile("(?i).*(bearer\\s+[a-z0-9._~+/=-]+|sk-[a-z0-9]{12,}|-----BEGIN .*PRIVATE KEY-----).*");
    private static final List<String> FORBIDDEN_POLICY_KEYS = List.of(
            "ownership",
            "owner",
            "dependency",
            "dependencies",
            "conflict",
            "trust",
            "trust_tier",
            "invalidation",
            "provenance",
            "parties",
            "binding"
    );

    /**
     * Validate a runtime binding against its frozen contract. The binding may tune allowed runtime
     * controls such as endpoint references and bounded timeouts; diagnostics identify any inline
     * credential material, contract drift, or forbidden semantic override.
     */
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
            if (binding.providerInstance() != null
                    && !Objects.equals(binding.providerInstance().componentUri(), contract.parties().provider().claimUri())) {
                diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "provider component_uri must match contract provider claim_uri", "provider_instance.component_uri"));
            }
            if (binding.providerInstance() != null
                    && !Objects.equals(binding.providerInstance().componentVersion(), contract.parties().provider().claimVersion())) {
                diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "provider component_version must match contract provider claim_version", "provider_instance.component_version"));
            }
            if (binding.consumerInstance() != null
                    && !Objects.equals(binding.consumerInstance().componentUri(), contract.parties().consumer().claimUri())) {
                diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "consumer component_uri must match contract consumer claim_uri", "consumer_instance.component_uri"));
            }
            if (binding.consumerInstance() != null
                    && !Objects.equals(binding.consumerInstance().componentVersion(), contract.parties().consumer().claimVersion())) {
                diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "consumer component_version must match contract consumer claim_version", "consumer_instance.component_version"));
            }
            if (binding.runtimePolicy() != null && binding.runtimePolicy().timeoutMs() != null) {
                if (binding.runtimePolicy().timeoutMs() <= 0) {
                    diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "runtime timeout_ms must be positive", "runtime_policy.timeout_ms"));
                }
                if (contract.expectations() != null
                        && contract.expectations().timeoutMs() != null
                        && binding.runtimePolicy().timeoutMs() > contract.expectations().timeoutMs()) {
                    diagnostics.add(Diagnostic.error(ErrorCode.BINDING_OVERRIDES_OWNERSHIP, "runtime timeout_ms cannot exceed frozen contract expectations", "runtime_policy.timeout_ms"));
                }
            }
        }
        if (binding.providerInstance() != null && binding.providerInstance().baseUrl() != null && binding.providerInstance().baseUrlRef() != null) {
            diagnostics.add(Diagnostic.error(ErrorCode.VALIDATION_FAILED, "base_url and base_url_ref are mutually exclusive", "provider_instance"));
        }
        scanMap("configuration.values", binding.configuration() == null ? Map.of() : binding.configuration().values(), diagnostics);
        scanMap("deployment_controls.values", binding.deploymentControls() == null ? Map.of() : binding.deploymentControls().values(), diagnostics);
        return new SchemaValidationReport(diagnostics);
    }

    /**
     * Recursive scanner: walks free-form configuration maps and collections because deployment
     * adapters may carry nested control blocks; every discovered secret-like value or forbidden key
     * is reported with a stable field path.
     */
    private void scanMap(String path, Map<String, Object> values, List<Diagnostic> diagnostics) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String keyPath = path + "." + entry.getKey();
            String key = entry.getKey() == null ? "" : entry.getKey();
            if (SECRET_KEY.matcher(key).matches() || looksLikeSecret(entry.getValue())) {
                diagnostics.add(Diagnostic.error(ErrorCode.BINDING_INLINE_SECRET, "runtime binding must use SecretRef/ConfigRef instead of inline credential material", keyPath));
            }
            if (isForbiddenOverrideKey(key)) {
                diagnostics.add(Diagnostic.error(ErrorCode.BINDING_OVERRIDES_OWNERSHIP, "runtime binding cannot override frozen contract ownership/dependency/conflict/trust/invalidation semantics", keyPath));
            }
            if (entry.getValue() instanceof Map<?, ?> nested) {
                scanMap(keyPath, castMap(nested), diagnostics);
            } else if (entry.getValue() instanceof Collection<?> collection) {
                int index = 0;
                for (Object item : collection) {
                    if (item instanceof Map<?, ?> nested) {
                        scanMap(keyPath + "[" + index + "]", castMap(nested), diagnostics);
                    } else if (looksLikeSecret(item)) {
                        diagnostics.add(Diagnostic.error(ErrorCode.BINDING_INLINE_SECRET, "runtime binding must use SecretRef/ConfigRef instead of inline credential material", keyPath + "[" + index + "]"));
                    }
                    index++;
                }
            }
        }
    }

    /**
     * Firewall helper: normalizes free-form keys so host-specific naming styles cannot bypass the
     * set of semantic fields that runtime bindings are forbidden to override.
     */
    private boolean isForbiddenOverrideKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_');
        return FORBIDDEN_POLICY_KEYS.stream().anyMatch(normalized::contains);
    }

    /**
     * Secret heuristic: catches common inline credential forms while allowing real deployments to
     * pass opaque references through the typed SecretRef/ConfigRef fields.
     */
    private boolean looksLikeSecret(Object value) {
        return value instanceof String stringValue && SECRET_VALUE.matcher(stringValue).matches();
    }

    /**
     * Map adapter: converts unknown nested map key types into diagnostic path strings without
     * mutating the caller's deployment-control data.
     */
    private Map<String, Object> castMap(Map<?, ?> values) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
