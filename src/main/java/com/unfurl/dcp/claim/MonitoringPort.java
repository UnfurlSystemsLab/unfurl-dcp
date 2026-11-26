package com.unfurl.dcp.claim;

import java.util.List;

/**
 * The monitoring integration surface (DCP HLD-C2 A.9): the health endpoints the component serves, and whether a
 * deployment must use its readiness and liveness checks. Each endpoint is an absolute path, never a URL: the host and
 * port are deployment facts (HLD-C2 §C).
 *
 * <p>Pattern: immutable <b>value object</b> that keeps presence: every member is a wrapper or a nullable list, so a member
 * the claim did not write stays null and {@code ClaimValidator} can reject a missing required member (HLD-C2 A.9, "A
 * declared port is complete"); nothing is defaulted. A list is frozen when present and stays null when absent.
 *
 * @param required          required: whether a deployment must monitor the component.
 * @param healthEndpoints   optional: the absolute paths of the component's health endpoints.
 * @param readinessRequired required: whether the component serves a readiness check a deployment must use.
 * @param livenessRequired  required: whether the component serves a liveness check a deployment must use.
 * @param notes             optional free-text notes.
 */
public record MonitoringPort(Boolean required, List<String> healthEndpoints, Boolean readinessRequired,
                             Boolean livenessRequired, String notes) {
    /** Compact constructor: freezes the endpoint list when present. */
    public MonitoringPort {
        healthEndpoints = healthEndpoints == null ? null : List.copyOf(healthEndpoints);
    }
}
