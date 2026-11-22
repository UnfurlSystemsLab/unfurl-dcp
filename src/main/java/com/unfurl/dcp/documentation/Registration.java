package com.unfurl.dcp.documentation;

import java.net.URI;
import java.util.Objects;

/**
 * Value Object: one capability a DCP HTTP host has registered right now (DCP HTTP Binding §4), with per-registration
 * provider evidence.
 *
 * <p>For {@link EntrySurface#HTTP} the provider is the host itself, and the provider fields repeat the report's identity.
 * For {@link EntrySurface#HOSTED_IN_PROCESS} they are the hosted component's identity and the claim hash the host verified
 * against the contract's pin at admission. {@link RegistrationReport} enforces the HTTP case.
 *
 * @param contractId           the registered contract
 * @param contractVersion      its version
 * @param capability           the provider capability
 * @param providerClaimUri     the provider's claim URI
 * @param providerClaimVersion the provider's claim version
 * @param providerClaimHash    the provider's canonical claim hash
 * @param surface              HTTP or HOSTED_IN_PROCESS
 * @param path                 {@code /dcp/<capability>} for HTTP; absent for hosted
 */
public record Registration(URI contractId, String contractVersion, String capability, URI providerClaimUri,
                           String providerClaimVersion, String providerClaimHash, EntrySurface surface, String path) {
    /** Validating constructor: required fields; HTTP registrations carry their DCP path. */
    public Registration {
        Objects.requireNonNull(contractId, "contract_id");
        Objects.requireNonNull(contractVersion, "contract_version");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(providerClaimUri, "provider_claim_uri");
        Objects.requireNonNull(providerClaimVersion, "provider_claim_version");
        Objects.requireNonNull(providerClaimHash, "provider_claim_hash");
        Objects.requireNonNull(surface, "surface");
        if (surface == EntrySurface.EXTERNAL) {
            throw new IllegalArgumentException("a host registers only HTTP or hosted in-process capabilities");
        }
        if (surface == EntrySurface.HTTP && (path == null || !path.equals("/dcp/" + capability))) {
            throw new IllegalArgumentException("an HTTP registration's path is /dcp/" + capability);
        }
    }
}
