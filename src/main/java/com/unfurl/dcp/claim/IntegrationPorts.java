package com.unfurl.dcp.claim;

import com.unfurl.dcp.value.DeepImmutable;

import java.util.Map;

/**
 * A claim's integration ports (DCP HLD-C2 A.9): the component's own named wiring, and the typed integration surfaces a
 * composition can read.
 *
 * <p>Pattern: immutable <b>value object</b>. Two kinds of entry, per the A.9 rules:
 * <ul>
 *   <li>{@code ports} is the component's own named port wiring (for example Foundry's runtime ports, name to
 *       implementation type). DCP carries it unchanged and never interprets it; it is deep-frozen so a hash-checked claim
 *       cannot change afterwards.</li>
 *   <li>{@code authentication}, {@code authorization}, {@code telemetry}, {@code monitoring} and {@code ai} are typed.
 *       Each is optional, and an absent one (null) means the claim does not declare that surface: never a default.</li>
 * </ul>
 * Absent typed ports are not serialized, so a claim that declares only {@code ports} keeps its canonical bytes and its
 * claim hash.
 *
 * @param ports          the component's own named wiring; empty when absent.
 * @param authentication the authentication surface, or null when not declared.
 * @param authorization  the authorization surface, or null when not declared.
 * @param telemetry      the telemetry surface, or null when not declared.
 * @param monitoring     the monitoring surface, or null when not declared.
 * @param ai             the AI surface, or null when not declared.
 */
public record IntegrationPorts(
        Map<String, Object> ports,
        AuthenticationPort authentication,
        AuthorizationPort authorization,
        TelemetryPort telemetry,
        MonitoringPort monitoring,
        AiPort ai
) {
    /** Compact constructor: deep-freezes the wiring map; typed ports stay as given (absent is null). */
    public IntegrationPorts {
        // Deep copy: nested maps and lists are frozen too, so the record cannot change after it is built.
        ports = DeepImmutable.map(ports);
    }

    /**
     * Compatibility constructor: only the component's own wiring, no typed surface declared.
     *
     * @param ports the component's own named wiring.
     */
    public IntegrationPorts(Map<String, Object> ports) {
        this(ports, null, null, null, null, null);
    }
}
