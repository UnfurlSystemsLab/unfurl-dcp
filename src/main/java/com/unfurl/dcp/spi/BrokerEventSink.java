package com.unfurl.dcp.spi;

import com.unfurl.dcp.broker.BrokerEvent;
import com.unfurl.substrate.policy.ExecutionContext;

public interface BrokerEventSink {
    void publish(BrokerEvent event, ExecutionContext context);
}
