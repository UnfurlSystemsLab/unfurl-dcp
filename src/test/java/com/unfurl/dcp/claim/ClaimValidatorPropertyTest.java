package com.unfurl.dcp.claim;

import com.unfurl.dcp.testing.Fixtures;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimValidatorPropertyTest {
    @Property(tries = 25)
    void validationIsDeterministic(@ForAll @IntRange(min = 0, max = 3) int variant) {
        Claim claim = variant(variant);
        ClaimValidator validator = new ClaimValidator();

        assertThat(validator.validate(claim).diagnostics())
                .isEqualTo(validator.validate(claim).diagnostics());
    }

    private Claim variant(int variant) {
        Claim base = Fixtures.validProviderClaim();
        return switch (variant) {
            case 0 -> base;
            case 1 -> new Claim(base.identity(), null, base.refusals(), base.dependencies(), base.offers(),
                    base.conflictResolution(), base.negotiationSurface(), base.integrationPorts(), base.faults(),
                    base.metadata());
            case 2 -> new Claim(base.identity(), base.domain(), List.of(), base.dependencies(), base.offers(),
                    base.conflictResolution(), base.negotiationSurface(), base.integrationPorts(), base.faults(),
                    base.metadata());
            default -> new Claim(base.identity(), base.domain(), base.refusals(), base.dependencies(), base.offers(),
                    base.conflictResolution(), base.negotiationSurface(), base.integrationPorts(), base.faults(),
                    new ClaimMetadata("0.1.0", base.identity().version(), Instant.EPOCH, Map.of()));
        };
    }
}
