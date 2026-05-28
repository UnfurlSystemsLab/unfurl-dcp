package com.unfurl.dcp.description;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimValidator;
import com.unfurl.dcp.manifest.WebappManifest;
import com.unfurl.dcp.manifest.WebappManifestValidator;
import com.unfurl.dcp.testing.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectorTest {
    @Test
    void claimProjectionUsesDescriptionAsSingleSourceOfTruth() {
        ComponentDescription description = description();

        Claim claim = new ClaimProjector().toClaim(description);

        assertThat(claim.identity()).isEqualTo(description.identity());
        assertThat(claim.domain()).isEqualTo(description.domain());
        assertThat(claim.refusals()).isEqualTo(description.refusals());
        assertThat(claim.offers()).isEqualTo(description.offers());
        assertThat(claim.metadata().claimVersion()).isEqualTo(description.identity().version());
        assertThat(new ClaimValidator().validate(claim).valid()).isTrue();
    }

    @Test
    void manifestProjectionUsesDescriptionIdentityAndOfferPermissions() {
        ComponentDescription description = description();

        WebappManifest manifest = new ManifestProjector().toManifest(description);
        Claim claim = new ClaimProjector().toClaim(description);

        assertThat(manifest.componentUri()).isEqualTo(description.identity().uri());
        assertThat(manifest.componentVersion()).isEqualTo(description.identity().version());
        assertThat(manifest.permissions()).containsExactly("capability:answer.search");
        assertThat(new WebappManifestValidator().validate(manifest, claim).valid()).isTrue();
    }

    private ComponentDescription description() {
        Claim claim = Fixtures.validProviderClaim();
        return new ComponentDescription(
                claim.identity(),
                claim.domain(),
                claim.refusals(),
                claim.dependencies(),
                claim.offers(),
                claim.conflictResolution(),
                claim.negotiationSurface(),
                claim.integrationPorts(),
                new ComponentMetadata(Map.of("source", "test")));
    }
}
