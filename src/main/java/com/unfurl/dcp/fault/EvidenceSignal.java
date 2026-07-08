package com.unfurl.dcp.fault;

/**
 * Evidence-source enum: names the classes of runtime observations that may
 * justify emitting a declared DCP fault signal.
 */
public enum EvidenceSignal {
    HEALTH,
    INVOCATION_ERROR,
    METRIC_THRESHOLD,
    POLICY_DENIAL,
    CONTRACT_INVALIDATION,
    EXTERNAL_EVENT
}
