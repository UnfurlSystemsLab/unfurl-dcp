package com.unfurl.dcp.contract;

import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.dcp.trust.TrustTier;
import com.unfurl.dcp.validation.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContractValidatorTest {
    @Test
    void rejectsC2CProvenanceWithoutModelId() {
        CompositionContract contract = withProvenance(new Provenance(CreatedBy.FABRIC, NegotiationMode.C2C, null, "0.2.0", false, Instant.EPOCH));

        assertThat(new ContractValidator().validate(contract).diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo(ErrorCode.CONTRACT_PROVENANCE_INCONSISTENT));
    }

    @Test
    void rejectsTrustTierThatDoesNotMatchCreatedBy() {
        CompositionContract base = Fixtures.validContract();
        CompositionContract contract = new CompositionContract(base.contractId(), base.contractVersion(), base.parties(), base.binding(),
                base.dataMapping(), base.transport(), base.expectations(),
                new Provenance(CreatedBy.EMBEDDED_SELF, NegotiationMode.H2H, null, "0.2.0", true, Instant.EPOCH),
                new Trust(TrustTier.NEUTRAL), base.invalidation());

        assertThat(new ContractValidator().validate(contract).diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo(ErrorCode.CONTRACT_TRUST_INCONSISTENT));
    }

    @Test
    void acceptsHardFailRuntimeViolationPolicy() {
        assertThat(new ContractValidator().validate(Fixtures.validContract()).valid()).isTrue();
    }

    private CompositionContract withProvenance(Provenance provenance) {
        CompositionContract base = Fixtures.validContract();
        return new CompositionContract(base.contractId(), base.contractVersion(), base.parties(), base.binding(),
                new DataMapping(Map.of(), Map.of()), base.transport(), base.expectations(), provenance, base.trust(),
                new Invalidation(List.of(), RuntimeViolationPolicy.HARD_FAIL));
    }
}
