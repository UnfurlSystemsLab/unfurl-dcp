package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.ArtifactRef;
import com.unfurl.dcp.documentation.DocumentationArtifactStore.ArtifactUnavailableException;
import com.unfurl.dcp.host.PackagedResources;
import com.unfurl.dcp.validation.ErrorCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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
 *   <li>{@link #fromArchive}: a jar read as data, never loading classes (Fabric's catalog scan). The archive is read into
 *       memory once, so later reads use the bytes that were first seen.</li>
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
     * Factory: a jar read as data. Base entries only; the archive is snapshotted at construction.
     *
     * @throws ArtifactUnavailableException when the archive cannot be read
     */
    public static PackagedArtifactStore fromArchive(Path jar) {
        Objects.requireNonNull(jar, "jar");
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) continue;
                byte[] bytes = zip.readNBytes(MAX_ENTRY_BYTES + 1);
                if (bytes.length > MAX_ENTRY_BYTES) {
                    throw new ArtifactUnavailableException(ErrorCode.ARTIFACT_UNAVAILABLE,
                            entry.getName() + " in " + jar.getFileName() + " is larger than " + MAX_ENTRY_BYTES + " bytes");
                }
                entries.putIfAbsent(entry.getName(), bytes);
            }
        } catch (IOException unreadable) {
            throw new ArtifactUnavailableException(ErrorCode.ARTIFACT_UNAVAILABLE,
                    jar.getFileName() + " cannot be read: " + unreadable.getMessage());
        }
        Map<String, byte[]> snapshot = Collections.unmodifiableMap(entries);
        return new PackagedArtifactStore(path -> Optional.ofNullable(snapshot.get(path)), String.valueOf(jar.getFileName()));
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
