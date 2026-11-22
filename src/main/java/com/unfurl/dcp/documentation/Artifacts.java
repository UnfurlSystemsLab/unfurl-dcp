package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.ArtifactRef;
import com.unfurl.dcp.documentation.DocumentationArtifactStore.ArtifactUnavailableException;
import com.unfurl.dcp.validation.ErrorCode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Utility: the hash check every artifact store applies, so the rule has one home (DCP HLD-C2 A.12).
 */
final class Artifacts {
    private Artifacts() { }

    /** Lower-case hex SHA-256 of the bytes. */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /** Rejects a malformed reference before any read. */
    static void requireWellFormed(ArtifactRef ref) {
        if (ref == null) throw new ArtifactUnavailableException(ErrorCode.ARTIFACT_REF_INVALID, "artifact reference is required");
        ref.shapeViolation().ifPresent(problem -> {
            throw new ArtifactUnavailableException(ErrorCode.ARTIFACT_REF_INVALID, problem);
        });
    }

    /**
     * Returns the bytes when they match the reference's hash.
     *
     * @param bytes the bytes found, or null when the file is missing
     * @param where a description of where they were looked for, without content
     */
    static byte[] verified(ArtifactRef ref, byte[] bytes, String where) {
        if (bytes == null) {
            throw new ArtifactUnavailableException(ErrorCode.ARTIFACT_UNAVAILABLE, ref.path() + " is not in " + where);
        }
        if (!sha256(bytes).equals(ref.sha256())) {
            throw new ArtifactUnavailableException(ErrorCode.ARTIFACT_HASH_MISMATCH,
                    ref.path() + " in " + where + " does not match its pinned sha256");
        }
        return bytes.clone();
    }
}
