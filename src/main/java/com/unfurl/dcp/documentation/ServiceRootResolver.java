package com.unfurl.dcp.documentation;

import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.runtimebinding.ProviderInstance;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.runtimebinding.RuntimeBindingValidator;
import com.unfurl.substrate.ports.host.ConfigResolver;
import com.unfurl.substrate.ports.host.ResolvedConfig;
import com.unfurl.substrate.policy.ExecutionContext;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Strategy: derives a provider's service root from its runtime binding (DCP HLD-C2 C, G.3). The binding is the only
 * endpoint source: no environment variable, profile, DNS lookup or network probe is consulted.
 *
 * <p>A literal {@code base_url} is used as written. A {@code base_url_ref} is resolved through the host's
 * {@link ConfigResolver} under the reference's own tenant ({@code config://<tenant>/...}), and the resolved document's
 * {@code value} is the endpoint, the same convention token-endpoint references use. The endpoint must be exactly
 * {@code <service_root>/dcp/<provider_capability>}, with no query or fragment.
 */
public final class ServiceRootResolver {
    private static final String CONFIG = "config://";

    /** Outcome of resolving one binding's endpoint. */
    public sealed interface ResolvedEndpoint {
        /** A well-shaped endpoint and the service root derived from it. */
        record ServiceRoot(URI endpoint, URI root) implements ResolvedEndpoint { }
        /** No endpoint: none declared, or a reference that does not resolve. */
        record Missing(String detail) implements ResolvedEndpoint { }
        /** An endpoint that is not {@code <service_root>/dcp/<capability>}. */
        record Mismatch(URI endpoint) implements ResolvedEndpoint { }
    }

    /**
     * Resolves one binding.
     *
     * @param binding        the provider's runtime binding
     * @param contract       its contract, for the capability name
     * @param configResolver resolves {@code base_url_ref}; may be null when the deployment mounts no configuration, in
     *                       which case a reference is {@code Missing}
     */
    public ResolvedEndpoint resolve(RuntimeBinding binding, CompositionContract contract, ConfigResolver configResolver) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(contract, "contract");
        ProviderInstance provider = binding.providerInstance();
        if (provider == null) return new ResolvedEndpoint.Missing("the binding has no provider_instance");
        URI endpoint;
        if (provider.baseUrl() != null) {
            endpoint = provider.baseUrl();
        } else if (provider.baseUrlRef() != null) {
            Optional<URI> resolved;
            try {
                resolved = fromReference(provider.baseUrlRef().uri(), configResolver);
            } catch (IllegalArgumentException malformed) {
                return new ResolvedEndpoint.Missing(malformed.getMessage());
            }
            if (resolved.isEmpty()) {
                return new ResolvedEndpoint.Missing("base_url_ref " + provider.baseUrlRef().uri() + " does not resolve to a value");
            }
            endpoint = resolved.get();
        } else {
            return new ResolvedEndpoint.Missing("the binding declares neither base_url nor base_url_ref");
        }
        return shape(endpoint, contract.binding().providerCapability());
    }

    /** Splits a well-shaped endpoint into its root; anything else is a mismatch. */
    static ResolvedEndpoint shape(URI endpoint, String capability) {
        String suffix = RuntimeBindingValidator.DCP_PATH_PREFIX + capability;
        String path = endpoint.getRawPath();
        if (!endpoint.isAbsolute() || endpoint.getRawAuthority() == null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null || path == null || !path.endsWith(suffix)) {
            return new ResolvedEndpoint.Mismatch(endpoint);
        }
        String rootPath = path.substring(0, path.length() - suffix.length());
        try {
            URI root = new URI(endpoint.getScheme(), endpoint.getRawAuthority(), rootPath.isEmpty() ? null : rootPath, null, null);
            return new ResolvedEndpoint.ServiceRoot(endpoint, URI.create(root.toString()));
        } catch (URISyntaxException malformed) {
            return new ResolvedEndpoint.Mismatch(endpoint);
        }
    }

    /** Resolves a {@code config://<tenant>/...} reference to its document's {@code value}. */
    private static Optional<URI> fromReference(String reference, ConfigResolver configResolver) {
        if (configResolver == null) {
            throw new IllegalArgumentException("base_url_ref " + reference + " cannot be resolved: no configuration is mounted");
        }
        if (!reference.startsWith(CONFIG) || reference.length() == CONFIG.length()) {
            throw new IllegalArgumentException("base_url_ref must be a config:// reference");
        }
        String rest = reference.substring(CONFIG.length());
        int slash = rest.indexOf('/');
        String tenant = slash <= 0 ? rest : rest.substring(0, slash);
        ExecutionContext context = new ExecutionContext(tenant, null, List.of(), List.of(), null, null, Map.of(), Map.of());
        Optional<ResolvedConfig> resolved = configResolver.resolve(reference, context);
        if (resolved.isEmpty() || !(resolved.get().values().get("value") instanceof String text) || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(URI.create(text));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("base_url_ref " + reference + " resolves to a malformed URL");
        }
    }
}
