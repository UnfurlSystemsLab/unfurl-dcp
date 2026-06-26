package com.unfurl.dcp.projection;

import java.net.URI;

public record DcpProjectionEdge(
        URI fromClaimUri,
        URI toClaimUri,
        String relationship
) {
    public DcpProjectionEdge {
        if (fromClaimUri == null) {
            throw new IllegalArgumentException("fromClaimUri is required");
        }
        if (toClaimUri == null) {
            throw new IllegalArgumentException("toClaimUri is required");
        }
        relationship = relationship == null || relationship.isBlank() ? "CONTAINS" : relationship;
    }
}
