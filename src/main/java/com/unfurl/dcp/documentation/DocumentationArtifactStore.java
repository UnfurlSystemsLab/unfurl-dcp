package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.ArtifactRef;
import com.unfurl.dcp.validation.ErrorCode;

/**
 * Port: the verified bytes of a claim's shipped artifacts (DCP HLD-C2 A.12). Every adapter verifies {@code sha256} over
 * the exact bytes before returning them, and never falls back to another copy, version or inferred document.
 */
@FunctionalInterface
public interface DocumentationArtifactStore {
    /**
     * Reads one referenced file.
     *
     * @param ref the reference; its shape has been validated by {@code ClaimValidator}
     * @return a copy of the file's verified bytes
     * @throws ArtifactUnavailableException when the file is missing ({@code ARTIFACT_UNAVAILABLE}) or its bytes do not
     *                                      match the hash ({@code ARTIFACT_HASH_MISMATCH})
     */
    byte[] read(ArtifactRef ref);

    /** Failure: a referenced file that is missing or does not match its pin. */
    final class ArtifactUnavailableException extends IllegalStateException {
        private final ErrorCode code;

        /** Constructor: the code is {@code ARTIFACT_UNAVAILABLE} or {@code ARTIFACT_HASH_MISMATCH}. */
        public ArtifactUnavailableException(ErrorCode code, String message) {
            super(message);
            if (code != ErrorCode.ARTIFACT_UNAVAILABLE && code != ErrorCode.ARTIFACT_HASH_MISMATCH && code != ErrorCode.ARTIFACT_REF_INVALID) {
                throw new IllegalArgumentException("unexpected artifact failure code " + code);
            }
            this.code = code;
        }

        /** The stable failure code. */
        public ErrorCode code() {
            return code;
        }
    }
}
