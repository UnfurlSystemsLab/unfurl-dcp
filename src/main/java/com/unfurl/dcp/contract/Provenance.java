package com.unfurl.dcp.contract;

import java.time.Instant;

public record Provenance(
        CreatedBy createdBy,
        NegotiationMode mode,
        String modelId,
        String fabricVersion,
        boolean humanInLoop,
        Instant createdAt
) {
}
