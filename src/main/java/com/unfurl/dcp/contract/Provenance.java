package com.unfurl.dcp.contract;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.Instant;

/**
 * Schema record: records how a frozen composition contract was authored. Jackson aliases preserve
 * snake_case wire compatibility, while the contract validator uses the mode/model/human flags to
 * enforce provenance consistency and trust-tier derivation.
 */
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
