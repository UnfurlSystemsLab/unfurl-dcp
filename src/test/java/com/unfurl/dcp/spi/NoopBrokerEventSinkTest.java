package com.unfurl.dcp.spi;

import com.unfurl.dcp.broker.BrokerEvent;
import com.unfurl.dcp.broker.BrokerEventType;
import com.unfurl.substrate.policy.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

class NoopBrokerEventSinkTest {
    @Test
    void publishIsSideEffectFree() {
        BrokerEvent event = new BrokerEvent(BrokerEventType.CLAIM_PRESENTED, URI.create("urn:claim"), null, null,
                "corr", null, Instant.EPOCH, Map.of());

        assertThatCode(() -> new NoopBrokerEventSink().publish(event, ExecutionContext.empty()))
                .doesNotThrowAnyException();
    }
}
