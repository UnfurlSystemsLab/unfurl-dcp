package com.unfurl.dcp.claim;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

public record ClaimMetadata(
        @NotBlank String dcpVersion,
        @NotBlank String claimVersion,
        Instant createdAt,
        Map<String, Object> extensions
) {
    public ClaimMetadata {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}
