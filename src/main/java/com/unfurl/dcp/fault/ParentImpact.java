package com.unfurl.dcp.fault;

/**
 * Parent-impact enum: records whether a child fault is suppressed or
 * propagated into a parent DCP graph as degraded or blocked service.
 */
public enum ParentImpact {
    NONE,
    DEGRADED,
    BLOCKED
}
