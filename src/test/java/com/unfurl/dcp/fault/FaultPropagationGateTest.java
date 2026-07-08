package com.unfurl.dcp.fault;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.testing.Fixtures;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FaultPropagationGate}; verifies deterministic DCP
 * parent-impact behavior without runtime renegotiation.
 */
class FaultPropagationGateTest {
    @Test
    void declaredFaultPropagatesWithDeclaredImpactAndAffectedSurfaces() {
        Claim claim = Fixtures.validProviderClaim();
        FaultSignal signal = signal("answer.search.timeout");

        FaultPropagationDecision decision = new FaultPropagationGate().evaluate(claim, signal);

        assertThat(decision.propagates()).isTrue();
        assertThat(decision.parentImpact()).isEqualTo(ParentImpact.DEGRADED);
        assertThat(decision.affectedNeeds()).containsExactly("answer.search");
        assertThat(decision.affectedOffers()).containsExactly("answer.search");
        assertThat(decision.reason()).contains("propagates");
    }

    @Test
    void undeclaredFaultDoesNotPropagate() {
        Claim claim = Fixtures.validProviderClaim();
        FaultSignal signal = signal("answer.search.unknown");

        FaultPropagationDecision decision = new FaultPropagationGate().evaluate(claim, signal);

        assertThat(decision.propagates()).isFalse();
        assertThat(decision.parentImpact()).isEqualTo(ParentImpact.NONE);
        assertThat(decision.reason()).contains("not declared");
    }

    /**
     * Fixture helper: creates a runtime fault signal with no affected surfaces so
     * the gate must fall back to the declaration's affected DCP surfaces.
     */
    private FaultSignal signal(String code) {
        return new FaultSignal(
                "fault-1",
                URI.create("urn:provider"),
                "provider-prod",
                URI.create("urn:contract"),
                URI.create("urn:binding"),
                "answer.search",
                code,
                FaultCategory.DEPENDENCY,
                FaultSeverity.DEGRADED,
                Instant.EPOCH,
                List.of(),
                List.of(),
                List.of(),
                List.of("trace://fault-1"),
                "corr-1");
    }
}
