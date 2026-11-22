package com.unfurl.dcp.runtimebinding;

import com.unfurl.dcp.contract.CompositionContract;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Value Object + Reader: the identity-context runtime binding as every composed component reads it (Fabric
 * HLD-needs-driven-identity §5; DCP LLD, <em>Runtime Binding</em>, identity binding reader).
 *
 * <p>Identity is an optional component, so the signed composition alone decides it (root rule 6): {@link #from} returns
 * empty exactly when no child contract provides {@value #IDENTITY_CAPABILITY}. When one does, the binding must be
 * complete. A component that cannot take the identity path refuses to start; it never runs a weaker mode. This class
 * reads values only. It never resolves the {@code config://} or {@code secret://} references, and never calls the
 * identity provider.
 *
 * <p>Audience coverage is checked here: the audience keys must equal exactly the protected components the compiler
 * recorded on the identity contract ({@value #NEEDED_BY}), so no component that needs identity lacks an audience.
 *
 * @param contractId           the identity contract's id
 * @param issuer               the exact token issuer
 * @param audiences            each protected component's claim URI and its token audience
 * @param tenantClaim          the tenant claim name
 * @param rolesClaim           the roles claim name
 * @param permissionsClaim     the permissions claim name
 * @param tokenEndpointRef     {@code config://} reference to the token endpoint
 * @param verificationKeysRef  {@code config://} reference to the folder of {@code <kid>.pem} verification keys
 * @param clientCredentialsRef {@code secret://} reference to the host's client credentials
 * @param serviceCredentials   each service that calls others as itself, by claim URI, and its {@code secret://} reference
 */
public record IdentityBinding(URI contractId, String issuer, Map<URI, String> audiences, String tenantClaim,
                              String rolesClaim, String permissionsClaim, String tokenEndpointRef,
                              String verificationKeysRef, String clientCredentialsRef,
                              Map<URI, String> serviceCredentials) {
    /** The capability of the identity contract. */
    public static final String IDENTITY_CAPABILITY = "identity-context";
    /** Contract metadata extension where the compiler records the components that depend on a capability. */
    public static final String NEEDED_BY = "neededBy";
    /** Stable failure code for every incomplete or inconsistent identity binding. */
    public static final String INCOMPLETE = "IDENTITY_BINDING_INCOMPLETE";

    /** Validating constructor: every value and reference is present and of the right scheme; maps are sorted copies. */
    public IdentityBinding {
        audiences = sortedCopy(audiences);
        serviceCredentials = sortedCopy(serviceCredentials);
        for (String value : new String[]{issuer, tenantClaim, rolesClaim, permissionsClaim}) {
            if (value == null || value.isBlank()) {
                throw failure(contractId, "needs its issuer and its tenant, roles and permissions claim names");
            }
        }
        requireScheme(tokenEndpointRef, "config://", "token_endpoint", contractId);
        requireScheme(verificationKeysRef, "config://", "verification_keys", contractId);
        requireScheme(clientCredentialsRef, "secret://", "client credentials", contractId);
        serviceCredentials.forEach((service, ref) -> requireScheme(ref, "secret://", "secret_refs." + service, contractId));
    }

    /** The audience of a protected component, when it takes tokens. */
    public Optional<String> audience(URI component) {
        return Optional.ofNullable(audiences.get(component));
    }

    /** The client-credentials reference of a service that calls others as itself, when the binding gives one. */
    public Optional<String> serviceCredentials(URI service) {
        return Optional.ofNullable(serviceCredentials.get(service));
    }

    /** The components the identity contract protects: exactly the audience keys. */
    public Set<URI> protectedComponents() {
        return audiences.keySet();
    }

    /**
     * Reader: finds the identity binding among a composition's children.
     *
     * @param children the composition's child contracts; callers pass the verified children, so identity can never be
     *                 removed by deleting a contract file
     * @param bindings the runtime bindings by contract id
     * @return empty when no child provides {@value #IDENTITY_CAPABILITY}
     * @throws IdentityBindingException ({@value #INCOMPLETE}) when there is more than one identity contract, or it has no
     *                                  binding, no {@value #NEEDED_BY} record, incomplete values, or audiences that do not
     *                                  cover exactly the protected components
     */
    public static Optional<IdentityBinding> from(List<CompositionContract> children, Map<URI, RuntimeBinding> bindings) {
        List<CompositionContract> identity = children.stream()
                .filter(contract -> contract.binding() != null
                        && IDENTITY_CAPABILITY.equals(contract.binding().providerCapability()))
                .toList();
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        if (identity.size() > 1) {
            throw failure(identity.get(0).contractId(), "is one of " + identity.size()
                    + " identity contracts; a composition has at most one");
        }
        CompositionContract contract = identity.get(0);
        URI contractId = contract.contractId();
        RuntimeBinding binding = bindings.get(contractId);
        if (binding == null || binding.configuration() == null) {
            throw failure(contractId, "has no runtime binding with configuration");
        }
        Set<URI> needing = neededBy(contract).orElseThrow(() -> failure(contractId,
                "does not record which components need " + IDENTITY_CAPABILITY + " (" + NEEDED_BY
                        + "); compile the composition again"));
        Map<String, Object> values = binding.configuration().values();
        Map<URI, String> audiences = uriMap(values.get("audiences"), "configuration.values.audiences", contractId);
        List<String> problems = new ArrayList<>();
        needing.stream().filter(component -> !audiences.containsKey(component)).map(URI::toString).sorted()
                .forEach(component -> problems.add("no audience for " + component + ", which needs " + IDENTITY_CAPABILITY));
        audiences.keySet().stream().filter(key -> !needing.contains(key)).map(URI::toString).sorted()
                .forEach(key -> problems.add(key + " does not need " + IDENTITY_CAPABILITY + ", so it takes no audience"));
        if (!problems.isEmpty()) {
            throw failure(contractId, "does not cover the components that need " + IDENTITY_CAPABILITY + ": "
                    + String.join("; ", problems));
        }
        Map<String, String> claims = stringMap(values.get("claims"), "configuration.values.claims", contractId);
        ConfigRef endpoint = binding.configuration().configRefs().get("token_endpoint");
        ConfigRef keys = binding.configuration().configRefs().get("verification_keys");
        SecretRef credentials = binding.providerInstance() == null ? null : binding.providerInstance().credentialsRef();
        Map<URI, String> services = new LinkedHashMap<>();
        binding.configuration().secretRefs().forEach((key, ref) -> services.put(uri(key, "secret_refs", contractId),
                ref == null ? null : ref.uri()));
        return Optional.of(new IdentityBinding(contractId, values.get("issuer") instanceof String issuer ? issuer : null,
                audiences, claims.get("tenant"), claims.get("roles"), claims.get("permissions"),
                endpoint == null ? null : endpoint.uri(), keys == null ? null : keys.uri(),
                credentials == null ? null : credentials.uri(), services));
    }

    /**
     * The claim URIs in the identity contract's {@value #NEEDED_BY} extension; empty when the record is absent or malformed,
     * which callers refuse. An absent record is never read as "nothing is protected".
     */
    private static Optional<Set<URI>> neededBy(CompositionContract contract) {
        Object recorded = contract.metadata() == null || contract.metadata().extensions() == null
                ? null : contract.metadata().extensions().get(NEEDED_BY);
        if (!(recorded instanceof List<?> list)
                || list.stream().anyMatch(item -> !(item instanceof String text) || text.isBlank())) {
            return Optional.empty();
        }
        try {
            Set<URI> result = new LinkedHashSet<>();
            list.forEach(item -> result.add(URI.create((String) item)));
            return Optional.of(result);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** A string-to-string mapping with non-blank values; nothing is coerced. */
    private static Map<String, String> stringMap(Object value, String field, URI contractId) {
        if (!(value instanceof Map<?, ?> map)) {
            throw failure(contractId, "needs " + field);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()
                    || !(entry.getValue() instanceof String text) || text.isBlank()) {
                throw failure(contractId, "has a malformed " + field);
            }
            result.put(key, text);
        }
        return result;
    }

    /** A claim-URI-to-string mapping. */
    private static Map<URI, String> uriMap(Object value, String field, URI contractId) {
        Map<URI, String> result = new LinkedHashMap<>();
        stringMap(value, field, contractId).forEach((key, text) -> result.put(uri(key, field, contractId), text));
        return result;
    }

    /** Parses one claim URI key. */
    private static URI uri(String key, String field, URI contractId) {
        try {
            return URI.create(key);
        } catch (IllegalArgumentException malformed) {
            throw failure(contractId, "has a malformed " + field + " key: " + key);
        }
    }

    /** Requires a reference of the given scheme with a non-empty remainder. */
    private static void requireScheme(String ref, String scheme, String what, URI contractId) {
        if (ref == null || !ref.startsWith(scheme) || ref.length() == scheme.length()) {
            throw failure(contractId, "needs a " + scheme + " " + what + " reference");
        }
    }

    /** A map copy sorted by key, so equal bindings are equal records. */
    private static Map<URI, String> sortedCopy(Map<URI, String> map) {
        return Collections.unmodifiableMap(new TreeMap<>(map == null ? Map.of() : map));
    }

    /** Builds the failure for this contract. */
    private static IdentityBindingException failure(URI contractId, String detail) {
        return new IdentityBindingException("the identity binding of " + contractId + " " + detail);
    }

    /** Failure: an identity binding that is present but cannot be used ({@value #INCOMPLETE}). */
    public static final class IdentityBindingException extends IllegalStateException {
        /** Constructor: the message names the contract and what is missing. */
        public IdentityBindingException(String message) {
            super(INCOMPLETE + ": " + message);
        }

        /** The stable failure code. */
        public String code() {
            return INCOMPLETE;
        }
    }
}
