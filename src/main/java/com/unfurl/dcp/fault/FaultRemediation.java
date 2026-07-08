package com.unfurl.dcp.fault;

import java.util.List;

/**
 * Value object: names the remediation actions a runtime or operator may take
 * while staying inside the component's declared DCP boundary.
 */
public record FaultRemediation(List<String> allowedActions) {
    /**
     * Compact constructor: freezes action names and treats an omitted block as
     * no declared remediation authority.
     */
    public FaultRemediation {
        allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
    }
}
