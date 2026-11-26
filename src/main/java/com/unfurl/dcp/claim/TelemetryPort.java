package com.unfurl.dcp.claim;

import java.util.List;

/**
 * The telemetry integration surface (DCP HLD-C2 A.9): the signals the component emits, and whether it needs a
 * correlation id to trace a call. {@code signals} lists only what the component really emits.
 *
 * <p>Pattern: immutable <b>value object</b> that keeps presence: every member is a wrapper or a nullable list, so a member
 * the claim did not write stays null and {@code ClaimValidator} can reject a missing required member (HLD-C2 A.9, "A
 * declared port is complete"); nothing is defaulted. A list is frozen when present and stays null when absent.
 *
 * @param required              required: whether a deployment must collect the component's telemetry.
 * @param signals               required: the signals emitted (an empty list is a value).
 * @param correlationIdRequired required: whether the component refuses or cannot trace a call without a correlation id.
 * @param notes                 optional free-text notes.
 */
public record TelemetryPort(Boolean required, List<TelemetrySignal> signals, Boolean correlationIdRequired,
                            String notes) {
    /** Compact constructor: freezes the signal list when present. */
    public TelemetryPort {
        signals = signals == null ? null : List.copyOf(signals);
    }
}
