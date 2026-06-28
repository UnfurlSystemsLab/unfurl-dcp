package com.unfurl.dcp.claim;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

/**
 * Schema record: carries protocol and claim-version metadata for a provider claim. Jackson aliases
 * accept the preferred snake_case wire names while Java code uses idiomatic camelCase; extensions
 * remain immutable free-form metadata for projection-specific hints.
 */
public record ClaimMetadata(
        @JsonAlias("dcp_version")
        @NotBlank String dcpVersion,
        @JsonAlias("claim_version")
        @NotBlank String claimVersion,
        @JsonAlias("created_at")
        Instant createdAt,
        Map<String, Object> extensions
) {
    /**
     * Defensive-copy constructor: prevents caller mutation of extension values at the map boundary
     * while preserving null-as-empty behavior for optional projection metadata.
     */
    public ClaimMetadata {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
