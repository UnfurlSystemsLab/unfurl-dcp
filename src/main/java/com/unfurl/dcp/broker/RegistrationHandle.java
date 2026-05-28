package com.unfurl.dcp.broker;

import java.net.URI;
import java.util.List;

public record RegistrationHandle(
        URI contractId,
        String contractVersion,
        URI claimUri,
        String claimVersion,
        List<String> exposedCapabilityNames
) {
    public RegistrationHandle {
        exposedCapabilityNames = exposedCapabilityNames == null ? List.of() : List.copyOf(exposedCapabilityNames);
    }

    public String stableId() {
        return contractId + "@" + contractVersion;
    }
}
