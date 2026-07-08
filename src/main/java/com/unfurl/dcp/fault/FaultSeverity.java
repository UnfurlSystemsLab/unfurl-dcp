package com.unfurl.dcp.fault;

/**
 * Fault severity enum: orders declared runtime faults from informational
 * signals through blocking failures that can invalidate a parent need.
 */
public enum FaultSeverity {
    INFO,
    WARNING,
    DEGRADED,
    CRITICAL,
    BLOCKING
}
