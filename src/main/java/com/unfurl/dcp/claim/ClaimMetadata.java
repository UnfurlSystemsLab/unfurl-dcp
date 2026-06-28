package com.unfurl.dcp.claim;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

public record ClaimMetadata(
        @JsonAlias("dcp_version")
        @NotBlank String dcpVersion,
        @JsonAlias("claim_version")
        @NotBlank String claimVersion,
        @JsonAlias("created_at")
        Instant createdAt,
        Map<String, Object> extensions
) {
    public ClaimMetadata {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
