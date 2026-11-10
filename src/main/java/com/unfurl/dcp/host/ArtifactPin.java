package com.unfurl.dcp.host;

import java.util.regex.Pattern;

/**
 * Value Object: one artifact a verified contract pins, by Maven coordinates and lower-case hex SHA-256. The coordinates
 * name the artifact in diagnostics; only the hash decides whether a delivered file is the pinned one.
 *
 * @param coordinates {@code group:artifact:version}, non-blank
 * @param sha256      64 lower-case hex characters
 */
public record ArtifactPin(String coordinates, String sha256) {
    static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** Validating constructor: rejects blank coordinates and anything but a lower-case hex SHA-256. */
    public ArtifactPin {
        if (coordinates == null || coordinates.isBlank()) throw new IllegalArgumentException("artifact coordinates are required");
        if (sha256 == null || !SHA256.matcher(sha256).matches())
            throw new IllegalArgumentException("artifact sha256 must be 64 lower-case hex characters: " + coordinates);
    }
}
