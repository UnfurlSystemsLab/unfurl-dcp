package com.unfurl.dcp.host;

import java.util.Optional;

/**
 * Port (read-only, data only): resources packaged in one admitted component's claim-bearing artifact, read from the
 * in-memory snapshot the host verified and loaded (LLD, <em>In-Process Component Host</em>, admission evidence).
 *
 * <p>It exists so documentation can describe a hosted component from exactly the bytes that were admitted: nothing is
 * read from disk again, and nothing is loaded as code. Paths resolve as the JDK resolves them for the running Java
 * version, multi-release overrides included.
 */
@FunctionalInterface
public interface PackagedResources {
    /**
     * Reads one packaged resource.
     *
     * @param path the resource path inside the artifact, with {@code /} separators and no leading slash
     * @return a copy of the resource's bytes, or empty when the artifact has no such resource
     */
    Optional<byte[]> read(String path);
}
