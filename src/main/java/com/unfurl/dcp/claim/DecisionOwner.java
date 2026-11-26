package com.unfurl.dcp.claim;

/** Who makes a component's authorization decision (DCP HLD-C2 A.9 {@code authorization.decision_owner}). */
public enum DecisionOwner {
    SELF,
    EXTERNAL_POLICY,
    HOST_APPLICATION,
    NOT_APPLICABLE
}
