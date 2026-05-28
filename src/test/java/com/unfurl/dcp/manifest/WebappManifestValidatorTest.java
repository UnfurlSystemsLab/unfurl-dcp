package com.unfurl.dcp.manifest;

import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.dcp.validation.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebappManifestValidatorTest {
    @Test
    void rejectsPermissionsNotDerivableFromClaim() {
        WebappManifest manifest = new WebappManifest(
                Fixtures.validProviderClaim().identity().uri(),
                Fixtures.validProviderClaim().identity().version(),
                "/",
                List.of(new Route("/", "answers")),
                new Navigation(List.of()),
                List.of("capability:answer.search", "capability:invented"),
                new ThemeContribution(ThemeMode.SUGGESTIVE, Map.of()),
                new Bootstrap(false, true));

        assertThat(new WebappManifestValidator().validate(manifest, Fixtures.validProviderClaim()).diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo(ErrorCode.MANIFEST_MISMATCH));
    }

    @Test
    void acceptsPermissionsDerivedFromClaimOffersAndConcerns() {
        WebappManifest manifest = new WebappManifest(
                Fixtures.validProviderClaim().identity().uri(),
                Fixtures.validProviderClaim().identity().version(),
                "/",
                List.of(new Route("/", "answers")),
                new Navigation(List.of()),
                List.of("capability:answer.search", "concern:answers"),
                new ThemeContribution(ThemeMode.SUGGESTIVE, Map.of()),
                new Bootstrap(false, true));

        assertThat(new WebappManifestValidator().validate(manifest, Fixtures.validProviderClaim()).valid()).isTrue();
    }
}
