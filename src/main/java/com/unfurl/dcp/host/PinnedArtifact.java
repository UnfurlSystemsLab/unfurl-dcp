package com.unfurl.dcp.host;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Immutable in-memory snapshot of one pinned jar (LLD, <em>In-Process Component Host</em>, stage 1). The host reads a
 * delivered file once into a byte array; that array is hashed against the pin and then parsed here, and every later
 * use, including class loading, reads this snapshot. The file path is never read again, so the bytes that were
 * verified are, by construction, the bytes that are inspected and loaded.
 *
 * <p>Parsing reads the archive as data: no class is loaded and no code runs. It computes the artifact's <em>effective
 * class set</em> for the running Java feature version exactly as the JDK reads a JAR, so the stage-1 rules, the class
 * loader and eager loading all agree on which classes the artifact holds:
 * <ul>
 *   <li>The artifact is multi-release only if its main manifest says {@code Multi-Release: true}. Otherwise entries
 *       under {@code META-INF/versions/} are ignored.</li>
 *   <li>In a multi-release artifact, a path outside {@code META-INF/} resolves to
 *       {@code META-INF/versions/N/<path>} for the highest integer {@code 9 <= N <= feature}, else its base entry.
 *       Paths under {@code META-INF/} are never versioned.</li>
 *   <li>Module descriptors and {@code package-info} entries are not loadable classes and are left out of
 *       {@link #classNames()}.</li>
 * </ul>
 * An archive that names the same entry twice is refused, so there is exactly one reading of every path.
 */
final class PinnedArtifact {
    private static final String VERSIONS = "META-INF/versions/";
    private static final String MANIFEST = "META-INF/MANIFEST.MF";

    private final ArtifactPin pin;
    private final String fileName;
    private final boolean classPathDeclared;
    private final Map<String, byte[]> entries;
    private final Map<String, String> effectiveEntries;
    private final Set<String> classNames;

    private PinnedArtifact(ArtifactPin pin, String fileName, boolean classPathDeclared, Map<String, byte[]> entries,
                           Map<String, String> effectiveEntries, Set<String> classNames) {
        this.pin = pin;
        this.fileName = fileName;
        this.classPathDeclared = classPathDeclared;
        this.entries = entries;
        this.effectiveEntries = effectiveEntries;
        this.classNames = classNames;
    }

    /**
     * Parses one jar snapshot whose SHA-256 has already been matched to {@code pin}.
     *
     * @param fileName the delivered file's name, for diagnostics only
     * @param snapshot the complete file bytes that were hashed
     * @param feature  the running Java feature version, {@code Runtime.version().feature()}
     * @throws IOException when the bytes are not a readable archive, hold no entry, or name an entry twice
     */
    static PinnedArtifact read(ArtifactPin pin, String fileName, byte[] snapshot, int feature) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(snapshot))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) continue;
                if (entries.putIfAbsent(entry.getName(), zip.readAllBytes()) != null) {
                    throw new IOException("the archive names an entry twice: " + entry.getName());
                }
            }
        }
        if (entries.isEmpty()) throw new IOException("the file is not a jar or holds no entries");

        Attributes main = new Attributes();
        byte[] manifest = entries.get(MANIFEST);
        if (manifest != null) main = new Manifest(new ByteArrayInputStream(manifest)).getMainAttributes();
        boolean multiRelease = "true".equalsIgnoreCase(main.getValue(Attributes.Name.MULTI_RELEASE));
        boolean classPath = main.getValue(Attributes.Name.CLASS_PATH) != null
                || main.getValue("Launcher-Agent-Class") != null;

        Map<String, String> effective = new TreeMap<>();
        Map<String, Integer> chosenVersion = new TreeMap<>();
        for (String name : entries.keySet()) {
            if (!name.startsWith(VERSIONS)) effective.put(name, name);
        }
        if (multiRelease) {
            for (String name : entries.keySet()) {
                if (!name.startsWith(VERSIONS)) continue;
                int slash = name.indexOf('/', VERSIONS.length());
                if (slash < 0) continue;
                int version = version(name.substring(VERSIONS.length(), slash));
                String logical = name.substring(slash + 1);
                // The JDK ignores versions below 9 or above the running feature, and never versions META-INF/.
                if (version < 9 || version > feature || logical.isEmpty() || logical.startsWith("META-INF/")) continue;
                if (version > chosenVersion.getOrDefault(logical, 0)) {
                    chosenVersion.put(logical, version);
                    effective.put(logical, name);
                }
            }
        }
        Set<String> classes = new TreeSet<>();
        for (String logical : effective.keySet()) {
            if (!logical.endsWith(".class") || logical.startsWith("META-INF/")) continue;
            String simple = logical.substring(logical.lastIndexOf('/') + 1);
            if (simple.equals("module-info.class") || simple.equals("package-info.class")) continue;
            classes.add(logical.substring(0, logical.length() - ".class".length()).replace('/', '.'));
        }
        return new PinnedArtifact(pin, fileName, classPath, Collections.unmodifiableMap(entries),
                Collections.unmodifiableMap(effective), Collections.unmodifiableSet(classes));
    }

    /** Parses a version directory name; anything that is not a plain positive integer is ignored, like the JDK does. */
    private static int version(String text) {
        try {
            return text.chars().allMatch(Character::isDigit) && !text.isEmpty() ? Integer.parseInt(text) : -1;
        } catch (NumberFormatException tooLarge) {
            return -1;
        }
    }

    /** Returns the pin this snapshot's SHA-256 matched. */
    ArtifactPin pin() {
        return pin;
    }

    /** Returns the delivered file's name, for diagnostics and resource URLs only. */
    String fileName() {
        return fileName;
    }

    /** Returns whether the manifest has {@code Class-Path} or {@code Launcher-Agent-Class}, which could add unpinned code. */
    boolean classPathDeclared() {
        return classPathDeclared;
    }

    /** Returns the binary names of the loadable classes in the effective class set, sorted. */
    Set<String> classNames() {
        return classNames;
    }

    /** Returns whether the effective class set holds this binary class name. */
    boolean hasClass(String binaryName) {
        return classNames.contains(binaryName);
    }

    /**
     * Returns the real entry name a logical path resolves to for the running Java version (its versioned override when
     * one applies), or null when the artifact has no such path.
     */
    String realName(String logicalPath) {
        return effectiveEntries.get(logicalPath);
    }

    /**
     * Returns an entry's content by its real name, or null when there is no such entry. This is the snapshot's own
     * array, handed only to the class loader, which reads it and never modifies or exposes it.
     */
    byte[] content(String realName) {
        return entries.get(realName);
    }

    /**
     * Returns the first entry, base or versioned for any version, that lies under a shared API package. Every version
     * directory is checked, including ones the running Java version ignores, so a newer runtime cannot reveal a shadow.
     */
    Optional<String> shadowOf(SharedApi shared) {
        for (String name : new TreeSet<>(entries.keySet())) {
            String logical = name;
            if (name.startsWith(VERSIONS)) {
                int slash = name.indexOf('/', VERSIONS.length());
                if (slash < 0) continue;
                logical = name.substring(slash + 1);
            }
            if (shared.sharesResource(logical)) return Optional.of(name);
        }
        return Optional.empty();
    }

    /** Returns whether a copy of the base metadata path exists under any {@code META-INF/versions/<N>/}. */
    boolean hasVersionedCopy(String basePath) {
        for (String name : entries.keySet()) {
            if (name.startsWith(VERSIONS) && name.endsWith("/" + basePath)
                    && name.indexOf('/', VERSIONS.length()) == name.length() - basePath.length() - 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a metadata entry from its base path.
     *
     * @param basePath a path under {@code META-INF/}, never versioned
     * @param maxBytes the inclusive size bound
     * @return the bytes, or empty when the artifact has no such entry
     * @throws IOException when the entry is larger than {@code maxBytes}
     */
    Optional<byte[]> metadata(String basePath, int maxBytes) throws IOException {
        byte[] bytes = entries.get(basePath);
        if (bytes == null) return Optional.empty();
        if (bytes.length > maxBytes) throw new IOException("entry is larger than " + maxBytes + " bytes");
        return Optional.of(bytes.clone());
    }
}
