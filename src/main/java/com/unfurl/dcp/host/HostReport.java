package com.unfurl.dcp.host;

import com.unfurl.dcp.componentconfig.EffectiveConfiguration;
import com.unfurl.dcp.spi.CapabilityRegistrar;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.unfurl.dcp.claim.Claim;

/**
 * Value Object: what one successful admission produced (LLD, <em>In-Process Component Host</em>, admission flow). A
 * host exposes the registrars to its callers and reports the rest in health and management views.
 *
 * @param registrars         each admitted component's own registrar of the host's shape, keyed by
 *                           {@code <claimUri>@<claimVersion>}
 * @param scopes             each admitted component's context, under the same keys; the host runs every call into the
 *                           registrar, and into anything it returns, through it
 * @param components         each admitted component's admission evidence under the same keys: the packaged claim the
 *                           host verified against the pin, the pin, and its packaged resources over the verified
 *                           snapshot (for documentation; HLD-C2 G.2 host view)
 * @param operations         one entry per admitted contract, ordered by contract id
 * @param ignoredArtifacts   delivered jars that no hosted contract pins; they were never loaded (closed world)
 * @param notHostedContracts verified contracts whose signed transport is not in-process, as {@code <id>@<version>}
 * @param <R>                the host's registrar shape
 */
public record HostReport<R extends CapabilityRegistrar>(Map<String, R> registrars, Map<String, ComponentScope> scopes,
                                                        Map<String, AdmittedComponent> components,
                                                        List<AdmittedOperation> operations, List<String> ignoredArtifacts,
                                                        List<String> notHostedContracts) {
    /**
     * Value Object: what the host verified when it admitted one component, kept so the component can be documented from
     * exactly that (DCP LLD, <em>Capability Documentation Projection</em>, host view). It outlives revocation: a revoked
     * contract leaves the registrar, but the evidence stays, and documentation renders only what is registered.
     *
     * @param claim     the packaged claim whose identity and canonical hash matched the contract's pin
     * @param pin       the pin it was checked against
     * @param resources the component's packaged resources, read from the verified snapshot
     */
    public record AdmittedComponent(Claim claim, ProviderArtifactPin pin, PackagedResources resources) {
        /** Validating constructor: every part is required. */
        public AdmittedComponent {
            java.util.Objects.requireNonNull(claim, "claim");
            java.util.Objects.requireNonNull(pin, "pin");
            java.util.Objects.requireNonNull(resources, "resources");
        }
    }

    /**
     * Value Object: one admitted operation.
     *
     * @param componentUri         the provider component's claim URI
     * @param componentVersion     the provider component's claim version
     * @param capability           the provider capability registered
     * @param contractId           the verified contract's id
     * @param contractVersion      the verified contract's version
     * @param configuration        the effective configuration with {@code secret-ref} values redacted
     * @param configurationOrigins where each effective value came from
     */
    public record AdmittedOperation(URI componentUri, String componentVersion, String capability, URI contractId,
                                    String contractVersion, Map<String, Object> configuration,
                                    Map<String, EffectiveConfiguration.Origin> configurationOrigins) {
        /** Defensive-copy constructor: the maps are sorted, unmodifiable copies. */
        public AdmittedOperation {
            configuration = Collections.unmodifiableMap(new TreeMap<>(configuration));
            configurationOrigins = Collections.unmodifiableMap(new TreeMap<>(configurationOrigins));
        }
    }

    /** Defensive-copy constructor: every collection is an unmodifiable copy in its given order; each registrar has a scope. */
    public HostReport {
        registrars = Collections.unmodifiableMap(new LinkedHashMap<>(registrars));
        scopes = Collections.unmodifiableMap(new LinkedHashMap<>(scopes));
        components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
        if (!scopes.keySet().equals(registrars.keySet())) {
            throw new IllegalArgumentException("every admitted component needs exactly one scope");
        }
        if (!components.keySet().equals(registrars.keySet())) {
            throw new IllegalArgumentException("every admitted component needs exactly one admission record");
        }
        operations = List.copyOf(operations);
        ignoredArtifacts = List.copyOf(ignoredArtifacts);
        notHostedContracts = List.copyOf(notHostedContracts);
    }
}
