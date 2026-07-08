package com.unfurl.dcp.claim;

import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.fault.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClaimValidator}; verifies structured diagnostics for
 * malformed claim metadata and first-class DCP fault vocabulary.
 */
class ClaimValidatorTest {
    @Test
    void missingDcpVersionReturnsStructuredDiagnostic() {
        Claim valid = Fixtures.validProviderClaim();
        Claim missingDcpVersion = new Claim(
                valid.identity(),
                valid.domain(),
                valid.refusals(),
                valid.dependencies(),
                valid.offers(),
                valid.conflictResolution(),
                valid.negotiationSurface(),
                valid.integrationPorts(),
                new ClaimMetadata(null, "1.0.0", Instant.EPOCH, Map.of()));

        assertThat(new ClaimValidator().validate(missingDcpVersion).diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo(ErrorCode.CLAIM_MALFORMED);
                    assertThat(diagnostic.fieldPath()).isEqualTo("metadata.dcp_version");
                });
    }

    @Test
    void faultDeclarationMustAffectDcpSurface() {
        Claim valid = Fixtures.validProviderClaim();
        Claim malformed = new Claim(
                valid.identity(),
                valid.domain(),
                valid.refusals(),
                valid.dependencies(),
                valid.offers(),
                valid.conflictResolution(),
                valid.negotiationSurface(),
                valid.integrationPorts(),
                new FaultPolicy(java.util.List.of(new FaultDeclaration(
                        "answer.search.timeout",
                        FaultCategory.DEPENDENCY,
                        FaultSeverity.DEGRADED,
                        "Search provider timed out",
                        FaultAffects.empty(),
                        new FaultEvidence(java.util.List.of(EvidenceSignal.INVOCATION_ERROR)),
                        new FaultPropagation(ParentImpact.DEGRADED, "active binding uses answer.search", ""),
                        new FaultRemediation(java.util.List.of("retry_with_backoff"))))),
                valid.metadata());

        assertThat(new ClaimValidator().validate(malformed).diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo(ErrorCode.FAULT_MALFORMED);
                    assertThat(diagnostic.fieldPath()).isEqualTo("faults.emitted.affects");
                });
    }

    @Test
    void propagatingFaultRequiresGateCondition() {
        Claim valid = Fixtures.validProviderClaim();
        Claim malformed = new Claim(
                valid.identity(),
                valid.domain(),
                valid.refusals(),
                valid.dependencies(),
                valid.offers(),
                valid.conflictResolution(),
                valid.negotiationSurface(),
                valid.integrationPorts(),
                new FaultPolicy(java.util.List.of(new FaultDeclaration(
                        "answer.search.timeout",
                        FaultCategory.DEPENDENCY,
                        FaultSeverity.DEGRADED,
                        "Search provider timed out",
                        new FaultAffects(java.util.List.of("answer.search"), java.util.List.of(), java.util.List.of()),
                        new FaultEvidence(java.util.List.of(EvidenceSignal.INVOCATION_ERROR)),
                        new FaultPropagation(ParentImpact.DEGRADED, " ", ""),
                        new FaultRemediation(java.util.List.of("retry_with_backoff"))))),
                valid.metadata());

        assertThat(new ClaimValidator().validate(malformed).diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo(ErrorCode.FAULT_MALFORMED);
                    assertThat(diagnostic.fieldPath()).isEqualTo("faults.emitted.propagation.propagates_when");
                });
    }
}
