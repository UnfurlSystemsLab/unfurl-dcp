package com.unfurl.dcp.spi;

import com.unfurl.dcp.broker.BrokerEvent;
import com.unfurl.substrate.policy.ExecutionContext;

public final class NoopBrokerEventSink implements BrokerEventSink {
    @Override
    public void publish(BrokerEvent event, ExecutionContext context) {
        // Intentionally no-op: DCP ships no telemetry or network side effects.
    }
}
