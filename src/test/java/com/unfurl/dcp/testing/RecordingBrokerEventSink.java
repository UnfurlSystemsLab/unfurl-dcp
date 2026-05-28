package com.unfurl.dcp.testing;

import com.unfurl.dcp.broker.BrokerEvent;
import com.unfurl.dcp.spi.BrokerEventSink;
import com.unfurl.substrate.policy.ExecutionContext;

import java.util.ArrayList;
import java.util.List;

public final class RecordingBrokerEventSink implements BrokerEventSink {
    private final List<BrokerEvent> events = new ArrayList<>();

    @Override
    public void publish(BrokerEvent event, ExecutionContext context) {
        events.add(event);
    }

    public List<BrokerEvent> events() {
        return List.copyOf(events);
    }
}
