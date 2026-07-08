package com.unfurl.dcp.fault;

/**
 * Fault taxonomy enum: classifies a DCP fault by the contract surface or
 * operational boundary that produced it.
 */
public enum FaultCategory {
    DEPENDENCY,
    CAPABILITY,
    CONSTRAINT,
    HEALTH,
    SECURITY,
    POLICY,
    RUNTIME
}
