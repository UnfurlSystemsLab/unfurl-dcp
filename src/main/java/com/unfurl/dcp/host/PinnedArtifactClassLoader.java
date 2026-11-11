package com.unfurl.dcp.host;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Class loader for one in-process component: it can define classes only from that component's pinned artifacts (LLD,
 * <em>In-Process Component Host</em>, stage 2). It reads the artifacts' in-memory {@link PinnedArtifact} snapshots, the
 * very bytes that were hashed against the pins, and never opens a file, so nothing on disk can change what it loads.
 * Its rules, in order:
 * <ol>
 *   <li>A class under a {@link SharedApi} prefix comes from the host's API loader only, so host and component share
 *       one copy of the types they exchange.</li>
 *   <li>A JDK class comes from the parent, which is the <em>platform</em> class loader, never the application loader:
 *       the host's own classpath is unreachable.</li>
 *   <li>Every other class is defined from the pinned snapshots, using each artifact's effective entry for the running
 *       Java version, exactly the entries stage 1 enumerated. Not found means {@link ClassNotFoundException}; there is
 *       no fallback to any other loader.</li>
 *   <li>Resources are searched in the pinned snapshots only, so service files and other resources on the host
 *       classpath are never visible. A returned URL names the selected entry, and reading it reads the snapshot.</li>
 *   <li>The artifact list is fixed at construction and never extended.</li>
 * </ol>
 * It deliberately does not extend {@code URLClassLoader}, whose manifest {@code Class-Path} handling and parent-first
 * delegation are the two ways a component could otherwise reach unpinned code.
 */
final class PinnedArtifactClassLoader extends ClassLoader implements AutoCloseable {
    static {
        registerAsParallelCapable();
    }

    private final SharedApi shared;
    private final List<PinnedArtifact> artifacts;
    private final Map<PinnedArtifact, ProtectionDomain> domains = new IdentityHashMap<>();
    private volatile boolean closed;

    /**
     * @param name      the loader name shown in diagnostics
     * @param artifacts the component's hash-verified snapshots, in pin order; the list is copied and never extended
     * @param shared    the shared API boundary
     */
    PinnedArtifactClassLoader(String name, List<PinnedArtifact> artifacts, SharedApi shared) {
        super(name, ClassLoader.getPlatformClassLoader());
        this.shared = shared;
        this.artifacts = List.copyOf(artifacts);
        for (PinnedArtifact artifact : this.artifacts) {
            domains.put(artifact, new ProtectionDomain(new CodeSource(location(artifact), (Certificate[]) null), null, this, null));
        }
    }

    /** Applies the delegation rules of the class comment. */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (shared.sharesClass(name)) {
                    loaded = shared.apiLoader().loadClass(name);
                } else {
                    try {
                        loaded = getParent().loadClass(name);
                    } catch (ClassNotFoundException notPlatform) {
                        loaded = findClass(name);
                    }
                }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    /** Defines a class from the first pinned snapshot that holds it; never looks anywhere else. */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (closed) throw new ClassNotFoundException(name + " (the component's pinned artifacts are closed)");
        String path = name.replace('.', '/') + ".class";
        for (PinnedArtifact artifact : artifacts) {
            String real = artifact.realName(path);
            if (real == null) continue;
            byte[] bytes = artifact.content(real);
            int dot = name.lastIndexOf('.');
            if (dot > 0 && getDefinedPackage(name.substring(0, dot)) == null) {
                try {
                    definePackage(name.substring(0, dot), null, null, null, null, null, null, null);
                } catch (IllegalArgumentException alreadyDefined) {
                    // Another thread defined the package first; both definitions are identical.
                }
            }
            return defineClass(name, bytes, 0, bytes.length, domains.get(artifact));
        }
        throw new ClassNotFoundException(name);
    }

    /** Resource lookup: pinned snapshots only, with no parent delegation. */
    @Override
    public URL getResource(String name) {
        return findResource(name);
    }

    /** Resource lookup: pinned snapshots only, with no parent delegation. */
    @Override
    public Enumeration<URL> getResources(String name) {
        return findResources(name);
    }

    /** Returns the first pinned artifact's copy of a resource, or null. */
    @Override
    protected URL findResource(String name) {
        Enumeration<URL> found = findResources(name);
        return found.hasMoreElements() ? found.nextElement() : null;
    }

    /** Returns every pinned artifact's copy of a resource, in pin order. */
    @Override
    protected Enumeration<URL> findResources(String name) {
        List<URL> urls = new ArrayList<>();
        if (closed) return Collections.enumeration(urls);
        for (PinnedArtifact artifact : artifacts) {
            String real = artifact.realName(name);
            if (real == null) continue;
            try {
                urls.add(URL.of(new URI("jar", location(artifact) + "!/" + real, null), new SnapshotHandler(artifact, real)));
            } catch (URISyntaxException | MalformedURLException impossible) {
                throw new IllegalStateException("pinned resource URL cannot be formed", impossible);
            }
        }
        return Collections.enumeration(urls);
    }

    /** Reads a resource straight from its pinned snapshot. */
    @Override
    public InputStream getResourceAsStream(String name) {
        if (closed) return null;
        for (PinnedArtifact artifact : artifacts) {
            String real = artifact.realName(name);
            if (real != null) return new ByteArrayInputStream(artifact.content(real));
        }
        return null;
    }

    /**
     * URL handler for one pinned resource. The URL reads like an ordinary {@code jar:} URL, but opening it reads the
     * entry from the artifact's in-memory snapshot. It never opens a file, so nothing on disk can be read through it,
     * and it stops working once the loader is closed.
     */
    private final class SnapshotHandler extends URLStreamHandler {
        private final PinnedArtifact artifact;
        private final String realName;

        private SnapshotHandler(PinnedArtifact artifact, String realName) {
            this.artifact = artifact;
            this.realName = realName;
        }

        /** Returns a connection whose stream is the snapshot entry's content. */
        @Override
        protected URLConnection openConnection(URL url) {
            return new URLConnection(url) {
                @Override public void connect() {
                    connected = true;
                }

                @Override public InputStream getInputStream() throws IOException {
                    if (closed) throw new IOException("the component's pinned artifacts are closed");
                    return new ByteArrayInputStream(artifact.content(realName));
                }

                @Override public long getContentLengthLong() {
                    return artifact.content(realName).length;
                }
            };
        }
    }

    /** Returns whether a class was defined by this loader from the given pinned artifact. */
    boolean definedFrom(Class<?> type, PinnedArtifact artifact) {
        return type.getClassLoader() == this && type.getProtectionDomain() == domains.get(artifact);
    }

    /**
     * Builds the code-source location shown for an artifact's classes. It is an identifier for diagnostics, built from
     * the pinned hash and file name; nothing is ever read from it.
     */
    private static URL location(PinnedArtifact artifact) {
        try {
            return new URI("file", "/pinned/" + artifact.pin().sha256() + "/" + artifact.fileName(), null).toURL();
        } catch (URISyntaxException | MalformedURLException impossible) {
            throw new IllegalStateException("pinned artifact location cannot be formed", impossible);
        }
    }

    /** Stops further class and resource loading. Classes already defined stay usable. */
    @Override
    public void close() {
        closed = true;
    }
}
