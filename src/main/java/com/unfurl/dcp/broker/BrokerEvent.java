package com.unfurl.dcp.broker;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

public record BrokerEvent(
        BrokerEventType type,
        URI claimUri,
        URI contractId,
        String capabilityName,
        String correlationId,
        DispositionReason reasonCode,
        Instant occurredAt,
        Map<String, Object> metadata
) {
    public BrokerEvent {
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
