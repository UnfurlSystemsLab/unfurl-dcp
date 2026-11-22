package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.ArtifactRef;
import com.unfurl.dcp.documentation.DocumentationArtifactStore.ArtifactUnavailableException;
import com.unfurl.dcp.host.PackagedResources;
import com.unfurl.dcp.validation.ErrorCode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Adapter: a component's own artifact, where a reference resolves by its {@code path} (DCP HLD-C2 A.12). Three factories
 * share one behaviour: resolve the path, then verify the hash.
 * <ul>
 *   <li>{@link #fromClassLoader}: a running host reading its own jar (the host view).</li>
 *   <li>{@link #fromArchive} and {@link #fromArchiveBytes}: a jar read as data, never loading classes (Fabric's catalog
 *       scan and bundle writer). The archive's bytes are held once, so later reads use the bytes that were first seen;
 *       an entry is located and size-checked only when a reference names it, so an unrelated large entry (for example a
 *       nested jar in a fat jar) never makes the store unusable.</li>
 *   <li>{@link #fromResources}: an admitted component's verified in-memory snapshot (DCP host admission evidence).</li>
 * </ul>
 */
public final class PackagedArtifactStore implements DocumentationArtifactStore {
    /** Upper bound on one entry read from an archive. */
    static final int MAX_ENTRY_BYTES = 16 * 1024 * 1024;

    private final Function<String, Optional<byte[]>> resolver;
    private final String where;

    private PackagedArtifactStore(Function<String, Optional<byte[]>> resolver, String where) {
        this.resolver = resolver;
        this.where = where;
    }

    /** Factory: resources visible to a class loader, normally the host's own artifact. */
    public static PackagedArtifactStore fromClassLoader(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        return new PackagedArtifactStore(path -> {
            try (InputStream in = loader.getResourceAsStream(path)) {
                return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
            } catch (IOException unreadable) {
                throw new ArtifactUnavailableException(ErrorCode.ARTIFACT_UNAVAILABLE, path + " cannot be read: " + unreadable.getMessage());
            }
        }, "the component's packaged resources");
    }

    /**
     * Factory: a jar read as data. Base entries only; the file's bytes are snapshotted at construction.
     *
     * @throws ArtifactUnavailableException when the archive cannot be read
     */
    public static PackagedArtifactStore fromArchive(Path jar) {
        Objects.requireNonNull(jar, "jar");
        try {
            return fromArchiveBytes(Files.readAllBytes(jar), String.valueOf(jar.getFileName()));
        } catch (IOException unreadable) {
            throw new ArtifactUnavailableException(ErrorCode.ARTIFACT_UNAVAILABLE,
                    jar.getFileName() + " cannot be read: " + unreadable.getMessage());
        }
    }

    /**
     * Factory: a jar's bytes read as data, for a reader that already holds them, such as Fabric's scanner, which hashed
     * exactly these bytes. Base entries only; the first entry with a name wins. A referenced entry larger than
     * {@value #MAX_ENTRY_BYTES} bytes, or an archive that cannot be parsed, fails that read with
     * {@code ARTIFACT_UNAVAILABLE}; entries no reference names are never read.
     *
     * @param archive the jar's bytes; copied, so later changes to the array are not seen
     * @param name    the jar's name, used in diagnostics
     */
    public static PackagedArtifactStore fromArchiveBytes(byte[] archive, String name) {
        Objects.requireNonNull(archive, "archive");
        byte[] snapshot = archive.clone();
        String where = name == null ? "the archive" : name;
        return new PackagedArtifactStore(path -> entry(snapshot, path, where), where);
    }

    /** Locates one base entry of a zip held in memory, reading at most {@value #MAX_ENTRY_BYTES} bytes of it. */
    private static Optional<byte[]> entry(byte[] archive, String path, String where) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory() || !entry.getName().equals(path)) continue;
                byte[] bytes = zip.readNBytes(MAX_ENTRY_BYTES + 1);
                if (bytes.length > MAX_ENTRY_BYTES) {
                    throw new ArtifactUnavailableException(ErrorCode.ARTIFACT_UNAVAILABLE,
                            path + " in " + where + " is larger than " + MAX_ENTRY_BYTES + " bytes");
                }
                return Optional.of(bytes);
            }
            return Optional.empty();
        } catch (IOException unreadable) {
            throw new ArtifactUnavailableException(ErrorCode.ARTIFACT_UNAVAILABLE,
                    where + " cannot be read: " + unreadable.getMessage());
        }
    }

    /** Factory: an admitted component's verified in-memory resources. */
    public static PackagedArtifactStore fromResources(PackagedResources resources) {
        Objects.requireNonNull(resources, "resources");
        return new PackagedArtifactStore(resources::read, "the admitted component's artifact");
    }

    /** Resolves by path and verifies the bytes. */
    @Override
    public byte[] read(ArtifactRef ref) {
        Artifacts.requireWellFormed(ref);
        return Artifacts.verified(ref, resolver.apply(ref.path()).orElse(null), where);
    }
}
