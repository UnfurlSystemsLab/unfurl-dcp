package com.unfurl.dcp.claim;

import com.unfurl.dcp.value.DeepImmutable;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Schema record: carries protocol and claim-version metadata for a provider claim. Jackson aliases
 * accept the preferred snake_case wire names while Java code uses idiomatic camelCase; extensions
 * remain immutable free-form metadata for projection-specific hints.
 *
 * <p>{@link #CONTAINMENT_BRIDGE_KEYS} are the extension keys that named an aggregate's children before
 * {@code child_claims} (DCP HLD-C2 A.14); a claim uses one or the other, never both.
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
    /** The containment bridge's keys in {@code extensions}, for claims written before {@code child_claims} (A.14). */
    public static final List<String> CONTAINMENT_BRIDGE_KEYS = List.of("contains", "children", "containsClaimUris", "childClaimUris");

    /**
     * Defensive-copy constructor: prevents caller mutation of extension values at the map boundary
     * while preserving null-as-empty behavior for optional projection metadata.
     */
    public ClaimMetadata {
        // Deep copy: nested maps and lists are frozen too, so the record cannot change after it is built.
        extensions = DeepImmutable.map(extensions);
    }
}
