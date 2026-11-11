package com.unfurl.dcp.host;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Value Object: the provider's complete runtime artifact set from a verified child contract (HLD-C §6.1 rule 2,
 * decision D1): the claim-bearing artifact, the canonical hash of its packaged claim, and every companion it needs at
 * runtime, each with its SHA-256. Nothing outside this set is loadable for the component.
 *
 * <p>Read from the composer's contract extensions (Fabric writes them at compile time):
 * <pre>
 * metadata.extensions:
 *   artifactCoordinates: com.unfurl.adapters.kotak:kotak-neo-core:0.1.0
 *   artifactSha256: "…"
 *   claimHash: "…"
 *   companionArtifacts:            # optional; omitted when the component needs none
 *     - { coordinates: com.fasterxml.jackson.core:jackson-core:2.17.2, sha256: "…" }
 * </pre>
 *
 * <p>Invariants: companion coordinates are unique and differ from the claim artifact's; the claim hash is a SHA-256.
 *
 * @param claimArtifact the artifact carrying the packaged claim
 * @param claimHash     {@code ClaimHash.canonical} of that claim
 * @param companions    the other pinned runtime artifacts, possibly empty
 */
public record ProviderArtifactPin(ArtifactPin claimArtifact, String claimHash, List<ArtifactPin> companions) {
    static final String EXT_COORDINATES = "artifactCoordinates";
    static final String EXT_SHA256 = "artifactSha256";
    static final String EXT_CLAIM_HASH = "claimHash";
    static final String EXT_COMPANIONS = "companionArtifacts";

    /** Validating constructor: checks the hash and the uniqueness of every pinned coordinate. */
    public ProviderArtifactPin {
        if (claimArtifact == null) throw new IllegalArgumentException("claim artifact pin is required");
        if (claimHash == null || !ArtifactPin.SHA256.matcher(claimHash).matches())
            throw new IllegalArgumentException("claimHash must be 64 lower-case hex characters");
        companions = companions == null ? List.of() : List.copyOf(companions);
        Set<String> seen = new HashSet<>();
        seen.add(claimArtifact.coordinates());
        for (ArtifactPin companion : companions) {
            if (!seen.add(companion.coordinates()))
                throw new IllegalArgumentException("artifact coordinates pinned twice: " + companion.coordinates());
        }
    }

    /** Returns the claim artifact followed by every companion. */
    public List<ArtifactPin> all() {
        List<ArtifactPin> all = new ArrayList<>(companions.size() + 1);
        all.add(claimArtifact);
        all.addAll(companions);
        return List.copyOf(all);
    }

    /**
     * Parser: reads the pin from verified contract extensions. Public so deployment tooling that delivers a hosted
     * component's artifacts (the emitter's admitted-component overlay, HLD-E §4.0b) selects exactly the set the host
     * will require, by the same rule.
     *
     * @param extensions the contract's {@code metadata.extensions}
     * @return empty when the contract pins no provider artifact ({@code artifactSha256} and {@code claimHash} both
     *         absent); the pin otherwise
     * @throws IllegalArgumentException when a pin is present but partial, mistyped or malformed
     */
    public static Optional<ProviderArtifactPin> fromExtensions(Map<String, Object> extensions) {
        Object sha = extensions.get(EXT_SHA256);
        Object claimHash = extensions.get(EXT_CLAIM_HASH);
        if (sha == null && claimHash == null) return Optional.empty();
        if (!(sha instanceof String shaText) || !(claimHash instanceof String hashText))
            throw new IllegalArgumentException("artifactSha256 and claimHash must both be strings");
        if (!(extensions.get(EXT_COORDINATES) instanceof String coordinates))
            throw new IllegalArgumentException("artifactCoordinates must be a string");
        return Optional.of(new ProviderArtifactPin(new ArtifactPin(coordinates, shaText), hashText,
                companions(extensions.get(EXT_COMPANIONS))));
    }

    /** Parser: {@code companionArtifacts} is absent or a list of {@code { coordinates, sha256 }} mappings. */
    private static List<ArtifactPin> companions(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("companionArtifacts must be a list");
        List<ArtifactPin> pins = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || map.size() != 2
                    || !(map.get("coordinates") instanceof String coordinates) || !(map.get("sha256") instanceof String sha))
                throw new IllegalArgumentException("each companion artifact must be exactly { coordinates, sha256 }");
            pins.add(new ArtifactPin(coordinates, sha));
        }
        return pins;
    }
}
