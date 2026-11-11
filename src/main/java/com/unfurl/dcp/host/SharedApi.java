package com.unfurl.dcp.host;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Value Object: the packages a component's code resolves from the host instead of from its own pinned artifacts (LLD,
 * <em>In-Process Component Host</em>, the shared boundary). They are the API the host and component exchange objects
 * through, so both must see one copy of each class: the host's.
 *
 * <p>DCP's base set is fixed: all of {@code com.unfurl.dcp.} and {@code com.unfurl.substrate.}, because a shared API
 * must be closed over its own signatures and DCP's types reference substrate's. A host adds the exact package prefixes
 * of its registrar shape (Foundry: {@code com.unfurl.foundry.substrate.}). Anything not listed must come from the
 * component's pinned artifacts; no pinned artifact may contain a class or resource under a listed prefix.
 *
 * @param packagePrefixes dotted package prefixes, each ending in a dot; always includes {@link #DCP_BASE}
 * @param apiLoader       the host's class loader that holds its single copy of the shared classes
 */
public record SharedApi(Set<String> packagePrefixes, ClassLoader apiLoader) {
    /** All of DCP and substrate: DCP's own types reference substrate composition, ports, policy and domain types. */
    public static final Set<String> DCP_BASE = Set.of("com.unfurl.dcp.", "com.unfurl.substrate.");

    private static final Pattern PREFIX = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*\\.)+");

    /** Validating constructor: prefixes are well-formed, include the base set, and are held as a sorted copy. */
    public SharedApi {
        Objects.requireNonNull(apiLoader, "apiLoader");
        Objects.requireNonNull(packagePrefixes, "packagePrefixes");
        for (String prefix : packagePrefixes) {
            if (prefix == null || !PREFIX.matcher(prefix).matches()) {
                throw new IllegalArgumentException("shared package prefix must be dotted and end with a dot: " + prefix);
            }
        }
        if (!packagePrefixes.containsAll(DCP_BASE)) {
            throw new IllegalArgumentException("shared API must include DCP's base prefixes " + DCP_BASE);
        }
        packagePrefixes = Collections.unmodifiableSet(new TreeSet<>(packagePrefixes));
    }

    /**
     * Factory: DCP's base set plus the host's own registrar-shape prefixes.
     *
     * @param apiLoader    the host's loader for the shared classes
     * @param hostPrefixes the host's additional package prefixes; may be empty
     */
    public static SharedApi of(ClassLoader apiLoader, Set<String> hostPrefixes) {
        Set<String> prefixes = new TreeSet<>(DCP_BASE);
        prefixes.addAll(Objects.requireNonNull(hostPrefixes, "hostPrefixes"));
        return new SharedApi(prefixes, apiLoader);
    }

    /** Returns whether a binary class name, such as {@code com.unfurl.dcp.host.HostPorts}, is shared. */
    public boolean sharesClass(String binaryName) {
        for (String prefix : packagePrefixes) {
            if (binaryName.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Returns whether a resource path, such as {@code com/unfurl/dcp/host/HostPorts.class}, lies in a shared package. */
    public boolean sharesResource(String path) {
        return sharesClass(path.replace('/', '.'));
    }
}
