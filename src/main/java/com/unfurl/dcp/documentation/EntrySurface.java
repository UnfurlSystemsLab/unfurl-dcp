package com.unfurl.dcp.documentation;

import com.unfurl.dcp.runtimebinding.DeploymentKind;

/**
 * Enumeration: how a documentation entry's capability is reached (DCP HLD-C2 G.1 table), decided by its runtime binding's
 * deployment kind.
 */
public enum EntrySurface {
    /** Served over the DCP HTTP binding at {@code POST <service_root>/dcp/<capability>}. */
    HTTP,
    /** Run in process by its host, the contract's consumer; it has no HTTP operation of its own. */
    HOSTED_IN_PROCESS,
    /** An external or webapp provider: listed, never probed, never invoked over DCP HTTP. */
    EXTERNAL;

    /**
     * Factory: the surface of a deployment kind.
     *
     * @param kind the binding's deployment kind; never null
     */
    public static EntrySurface of(DeploymentKind kind) {
        return switch (kind) {
            case CONTAINER, REMOTE_SERVICE, SIDECAR -> HTTP;
            case IN_PROCESS -> HOSTED_IN_PROCESS;
            case EXTERNAL_SAAS, WEBAPP -> EXTERNAL;
        };
    }
}
