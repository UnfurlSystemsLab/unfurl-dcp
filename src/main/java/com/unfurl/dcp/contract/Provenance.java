package com.unfurl.dcp.contract;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.Instant;

public record Provenance(
        @JsonAlias("created_by")
        CreatedBy createdBy,
        @JsonAlias("negotiation_mode")
        NegotiationMode mode,
        @JsonAlias("model_id")
        String modelId,
        @JsonAlias("fabric_version")
        String fabricVersion,
        @JsonAlias("human_in_loop")
        boolean humanInLoop,
        @JsonAlias("created_at")
        Instant createdAt
) {
}
