package com.unfurl.dcp.claim;

import java.net.URI;
import java.util.Objects;

/**
 * Value Object: a claim's identity as contracts pin it, its URI and version. It is the key by which deployment readers
 * find the claim of a contract party.
 *
 * @param uri     the claim identity URI
 * @param version the claim identity version
 */
public record ClaimKey(URI uri, String version) {
    /** Validating constructor: both parts are required. */
    public ClaimKey {
        Objects.requireNonNull(uri, "uri");
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version is required");
        }
    }

    /** Factory: the key of a claim's own identity. */
    public static ClaimKey of(Claim claim) {
        return new ClaimKey(claim.identity().uri(), claim.identity().version());
    }

    /** The {@code <uri>@<version>} form used in diagnostics and host keys. */
    @Override
    public String toString() {
        return uri + "@" + version;
    }
}
