package com.unfurl.dcp.host;

import java.util.Optional;
import java.util.Set;

/**
 * Port: the neutral host services a host has actually bound, by their substrate port type (for example
 * {@code OperationAuditSink.class} or {@code ConfigResolver.class}). A component's activation adapts them to the
 * component's own ports (HLD-C §6.1 rules 3 and 4).
 *
 * <p>A host exposes exactly what its deployment bound. It never returns a default, permissive or no-op service: a type
 * that is not bound is absent, and a component that needs it is refused with {@code HOST_DEPENDENCY_UNMET}.
 */
public interface HostPorts {
    /**
     * Returns the bound service of one port type.
     *
     * @param type a neutral port interface from {@code substrate-ports}
     * @return the service, or empty when the host binds none of that type
     */
    <T> Optional<T> port(Class<T> type);

    /** Returns every port type the host binds; {@link #port} is present exactly for these. */
    Set<Class<?>> boundTypes();
}
