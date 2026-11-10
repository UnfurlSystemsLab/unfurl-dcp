package com.unfurl.dcp.contract;

import java.util.Map;

/**
 * Schema record: how a contract's provider is reached, with transport-specific details.
 *
 * @param kind    the transport kind
 * @param details open-ended, JSON-shaped transport details; never null and deeply immutable
 */
public record Transport(TransportKind kind, Map<String, Object> details) {
    /**
     * Defensive-copy constructor: treats missing details as empty and freezes them deeply ({@link DeepImmutable}), so
     * nested maps and lists cannot be edited through a contract after it is built or verified.
     */
    public Transport {
        details = DeepImmutable.map(details);
    }
}
