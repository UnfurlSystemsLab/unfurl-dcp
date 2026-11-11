package com.unfurl.dcp.host;

import com.unfurl.dcp.componentconfig.EffectiveConfiguration;
import com.unfurl.dcp.spi.CapabilityRegistrar;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Value Object: what one successful admission produced (LLD, <em>In-Process Component Host</em>, admission flow). A
 * host exposes the registrars to its callers and reports the rest in health and management views.
 *
 * @param registrars         each admitted component's own registrar of the host's shape, keyed by
 *                           {@code <claimUri>@<claimVersion>}
 * @param operations         one entry per admitted contract, ordered by contract id
 * @param ignoredArtifacts   delivered jars that no hosted contract pins; they were never loaded (closed world)
 * @param notHostedContracts verified contracts whose signed transport is not in-process, as {@code <id>@<version>}
 * @param <R>                the host's registrar shape
 */
public record HostReport<R extends CapabilityRegistrar>(Map<String, R> registrars, List<AdmittedOperation> operations,
                                                        List<String> ignoredArtifacts, List<String> notHostedContracts) {
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

    /** Defensive-copy constructor: every collection is an unmodifiable copy in its given order. */
    public HostReport {
        registrars = Collections.unmodifiableMap(new LinkedHashMap<>(registrars));
        operations = List.copyOf(operations);
        ignoredArtifacts = List.copyOf(ignoredArtifacts);
        notHostedContracts = List.copyOf(notHostedContracts);
    }
}
