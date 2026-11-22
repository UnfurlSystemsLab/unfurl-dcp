package com.unfurl.dcp.claim;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Value Object: one file shipped inside the artifact that carries a claim, pinned by content hash (DCP HLD-C2 A.12).
 *
 * <p>The path resolves only inside that artifact, and the hash freezes the bytes: because the claim hash covers this
 * reference, a contract that pins the claim also pins the file. Readers verify the hash before use, and a mismatch is a
 * failure, never a fallback.
 *
 * <p>The record itself is lenient so that a malformed reference in a parsed claim becomes a {@link ClaimValidator}
 * diagnostic instead of a parse exception. {@link #shapeViolation()} is the single definition of a well-formed
 * reference, shared by the validator and the artifact stores.
 *
 * @param path      relative path inside the claim's own artifact, with {@code /} separators
 * @param mediaType what the file is; which types a field accepts is a claim rule
 * @param sha256    lower-case hex SHA-256 of the file's exact bytes
 */
public record ArtifactRef(String path, ArtifactMediaType mediaType, String sha256) {
    /** Lower-case hex SHA-256, the only accepted hash form. */
    public static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

    /**
     * Shape rule (A.12): the path is relative, non-empty and stays inside the artifact, the media type is present, and the
     * hash is 64 lower-case hex characters. It checks the reference only; whether the file exists and matches is checked
     * by whoever holds the bytes.
     *
     * @return the first violation, or empty when the reference is well formed
     */
    public Optional<String> shapeViolation() {
        if (path == null || path.isBlank()) {
            return Optional.of("path is required");
        }
        if (path.startsWith("/")) {
            return Optional.of("path must be relative: " + path);
        }
        if (path.contains("\\")) {
            return Optional.of("path must use / separators: " + path);
        }
        if (SCHEME.matcher(path).find()) {
            return Optional.of("path must not carry a URI scheme: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                return Optional.of("path has an empty segment: " + path);
            }
            if (segment.equals("..") || segment.equals(".")) {
                return Optional.of("path must not contain . or .. segments: " + path);
            }
        }
        if (mediaType == null) {
            return Optional.of("media_type is required");
        }
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            return Optional.of("sha256 must be 64 lower-case hex characters");
        }
        return Optional.empty();
    }
}
