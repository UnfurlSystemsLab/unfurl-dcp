package com.unfurl.dcp.documentation;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Value Object: what a DCP HTTP host has registered right now, served at {@code GET <service_root>/dcp/registrations}
 * (DCP HTTP Binding §4). It lists contracts only: no inputs, outputs, credentials or caller data.
 *
 * @param componentUri     the host's claim identity URI
 * @param componentVersion the host's claim version
 * @param claimHash        canonical hash of the host's packaged claim
 * @param observedAt       when the host produced the report
 * @param registrations    the capabilities registered right now, sorted by contract
 * @param servedSurfaces   the product API surfaces this instance serves, sorted
 */
public record RegistrationReport(URI componentUri, String componentVersion, String claimHash, Instant observedAt,
                                 List<Registration> registrations, List<String> servedSurfaces) {
    /**
     * Validating constructor: required fields; an HTTP registration's provider evidence equals the report's own identity,
     * so a host never reports a hosted component under its own identity or the reverse; contracts are unique.
     */
    public RegistrationReport {
        Objects.requireNonNull(componentUri, "component_uri");
        Objects.requireNonNull(componentVersion, "component_version");
        Objects.requireNonNull(claimHash, "claim_hash");
        Objects.requireNonNull(observedAt, "observed_at");
        registrations = registrations == null ? List.of() : registrations.stream()
                .sorted(Comparator.comparing((Registration r) -> r.contractId().toString()).thenComparing(Registration::contractVersion))
                .toList();
        servedSurfaces = servedSurfaces == null ? List.of() : servedSurfaces.stream().sorted().distinct().toList();
        for (Registration registration : registrations) {
            if (registration.surface() == EntrySurface.HTTP
                    && !(registration.providerClaimUri().equals(componentUri)
                    && registration.providerClaimVersion().equals(componentVersion)
                    && registration.providerClaimHash().equals(claimHash))) {
                // Fixed text: the contract id came from the body, and decode errors never quote it.
                throw new WireRuleViolation("an HTTP registration must carry the host's own identity and claim hash");
            }
        }
        if (registrations.stream().map(r -> r.contractId() + "@" + r.contractVersion()).distinct().count() != registrations.size()) {
            throw new WireRuleViolation("a contract is registered at most once");
        }
    }

    /** Lookup: the registration of one contract version, when the host lists it. */
    public Optional<Registration> registration(URI contractId, String contractVersion) {
        return registrations.stream()
                .filter(r -> r.contractId().equals(contractId) && r.contractVersion().equals(contractVersion))
                .findFirst();
    }
}
