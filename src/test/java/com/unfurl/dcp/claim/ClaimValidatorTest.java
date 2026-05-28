package com.unfurl.dcp.claim;

import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.dcp.validation.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
