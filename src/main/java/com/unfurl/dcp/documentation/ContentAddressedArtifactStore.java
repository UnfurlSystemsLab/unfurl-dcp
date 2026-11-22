package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.ArtifactRef;
import com.unfurl.dcp.documentation.DocumentationArtifactStore.ArtifactUnavailableException;
import com.unfurl.dcp.validation.ErrorCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Adapter: a deployment bundle's content-addressed artifact store, {@code artifacts/sha256/<sha256>} (DCP HLD-C2 A.12).
 * A reference is resolved by its hash; its {@code path} keeps its meaning inside the component artifact only. The bytes
 * are re-hashed on every read, so a tampered file is a mismatch, never a silent substitute.
 */
public final class ContentAddressedArtifactStore implements DocumentationArtifactStore {
    private final Path directory;

    /**
     * Constructor.
     *
     * @param directory the bundle's {@code artifacts/sha256} directory
     */
    public ContentAddressedArtifactStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** Reads by hash and verifies the bytes. */
    @Override
    public byte[] read(ArtifactRef ref) {
        Artifacts.requireWellFormed(ref);
        Path file = directory.resolve(ref.sha256());
        if (!Files.isRegularFile(file)) {
            return Artifacts.verified(ref, null, "the artifact store");
        }
        try {
            return Artifacts.verified(ref, Files.readAllBytes(file), "the artifact store");
        } catch (IOException unreadable) {
            throw new ArtifactUnavailableException(ErrorCode.ARTIFACT_UNAVAILABLE,
                    ref.path() + " cannot be read from the artifact store: " + unreadable.getMessage());
        }
    }
}
