package com.unfurl.dcp.broker;

import java.net.URI;
import java.util.List;

/**
 * Handle record: identifies the capabilities exposed by one accepted frozen contract registration.
 * Hosts store this value to revoke or invalidate dynamic composition later without re-running
 * negotiation or looking up provider claims.
 */
public record RegistrationHandle(
        URI contractId,
        String contractVersion,
        URI claimUri,
        String claimVersion,
        List<String> exposedCapabilityNames
) {
    /**
     * Defensive-copy constructor: makes exposed capability names immutable so revoke/invalidate
     * operations target the exact capabilities registered during accept.
     */
    public RegistrationHandle {
        exposedCapabilityNames = exposedCapabilityNames == null ? List.of() : List.copyOf(exposedCapabilityNames);
    }

    /**
     * Stable handle id: combines contract id and version for adapter metadata and audit correlation.
     */
    public String stableId() {
        return contractId + "@" + contractVersion;
    }
}
