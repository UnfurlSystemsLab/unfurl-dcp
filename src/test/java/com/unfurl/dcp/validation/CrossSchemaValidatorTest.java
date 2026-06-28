package com.unfurl.dcp.validation;

import com.unfurl.dcp.claim.*;
import com.unfurl.dcp.manifest.*;
import com.unfurl.dcp.runtimebinding.*;
import com.unfurl.dcp.testing.Fixtures;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CrossSchemaValidatorTest {
    private final CrossSchemaValidator validator = new CrossSchemaValidator();

    @Test
    void validatesClaimManifestIdentityMatch() {
        WebappManifest manifest = new WebappManifest(URI.create("urn:other"), "1.0.0", "/", List.of(), new Navigation(List.of()), List.of(),
                new ThemeContribution(ThemeMode.SUGGESTIVE, Map.of()), new Bootstrap(false, true));

        assertThat(validator.validate(Fixtures.validProviderClaim(), manifest).diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo(ErrorCode.MANIFEST_MISMATCH));
    }

    @Test
    void validatesContractProviderCapabilityAndVersion() {
        Claim provider = claimWithOfferVersion("0.9.0");

        assertThat(validator.validate(Fixtures.validContract(), Map.of(URI.create("urn:provider"), provider)).diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo(ErrorCode.OFFER_VERSION_UNSATISFIED));
    }

    @Test
    void validatesBothContractPartiesArePresent() {
        assertThat(validator.validate(Fixtures.validContract(), Map.of()).diagnostics())
                .extracting(Diagnostic::fieldPath)
                .contains("parties.consumer", "parties.provider");
    }

    @Test
    void warnsWhenSuppliedClaimVersionHasDriftedPastContractPin() {
        Claim provider = claimWithIdentityVersion("urn:provider", "2.0.0");
        Claim consumer = claimWithIdentityVersion("urn:consumer", "1.0.0");

        assertThat(validator.validate(Fixtures.validContract(), Map.of(
                        URI.create("urn:provider"), provider,
                        URI.create("urn:consumer"), consumer)).diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.severity()).isEqualTo(Severity.WARNING);
                    assertThat(diagnostic.code()).isEqualTo(ErrorCode.CONTRACT_INVALIDATED);
                    assertThat(diagnostic.fieldPath()).isEqualTo("parties.provider.claim_version");
                    assertThat(diagnostic.metadata()).containsEntry("pinned_version", "1.0.0");
                    assertThat(diagnostic.metadata()).containsEntry("supplied_version", "2.0.0");
                });
    }

    @Test
    void validatesRuntimeBindingContractPin() {
        RuntimeBinding binding = new RuntimeBinding(URI.create("urn:binding"), URI.create("urn:other"), "1.0.0",
                null, null, null, null, new Configuration(Map.of()), new DeploymentControls(Map.of()), null);

        assertThat(validator.validate(binding, Fixtures.validContract()).diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.fieldPath()).isEqualTo("contract_id"));
    }

    private Claim claimWithOfferVersion(String version) {
        Claim base = Fixtures.validProviderClaim();
        return new Claim(base.identity(), base.domain(), base.refusals(), base.dependencies(),
                List.of(new Offer("answer.search", "Search", ConsumerAccess.ANY, null, Stability.STABLE, version, false, null)),
                base.conflictResolution(), base.negotiationSurface(), base.integrationPorts(),
                new ClaimMetadata("0.2.0", "1.0.0", Instant.EPOCH, Map.of()));
    }

    private Claim claimWithIdentityVersion(String uri, String version) {
        Claim base = Fixtures.validProviderClaim();
        Identity identity = new Identity(URI.create(uri), "Component", ComponentKind.COMPONENT, version, "Unfurl", URI.create("urn:publisher"));
        return new Claim(identity, base.domain(), base.refusals(), base.dependencies(), base.offers(),
                base.conflictResolution(), base.negotiationSurface(), base.integrationPorts(),
                new ClaimMetadata("0.2.0", version, Instant.EPOCH, Map.of()));
    }
}
