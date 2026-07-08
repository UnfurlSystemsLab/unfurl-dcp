package com.unfurl.dcp.resolver;

import com.unfurl.dcp.claim.*;
import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.versioning.SemverRange;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityResolverTest {
    @Test
    void choosesHighestSemverMatch() {
        Claim provider = providerWithOffers(
                new Offer("answer.search", "old", ConsumerAccess.ANY, null, Stability.STABLE, "1.9.0", false, null),
                new Offer("answer.search", "new", ConsumerAccess.ANY, null, Stability.STABLE, "1.10.0", false, null));

        ResolutionResult result = new CapabilityResolver().resolve(new ResolutionRequest(
                "answer.search", null, new SemverRange(">=1.0.0"), URI.create("urn:consumer"), List.of(provider)));

        assertThat(result.resolved()).isTrue();
        assertThat(result.resolvedOfferVersion()).isEqualTo("1.10.0");
    }

    @Test
    void enforcesNamedConsumerAccessPolicy() {
        Claim provider = providerWithOffers(new Offer("answer.search", "named", ConsumerAccess.NAMED_COMPONENTS_ONLY, null, Stability.STABLE, "1.0.0", false, null));

        ResolutionResult denied = new CapabilityResolver().resolve(new ResolutionRequest(
                "answer.search", null, new SemverRange(">=1.0.0"), URI.create("urn:consumer"), Map.of(), List.of(provider)));
        ResolutionResult allowed = new CapabilityResolver().resolve(new ResolutionRequest(
                "answer.search", null, new SemverRange(">=1.0.0"), URI.create("urn:consumer"),
                Map.of("answer.search", new AccessPolicy(ConsumerAccess.NAMED_COMPONENTS_ONLY, Set.of(URI.create("urn:consumer")))),
                List.of(provider)));

        assertThat(denied.resolved()).isFalse();
        assertThat(allowed.resolved()).isTrue();
    }

    @Test
    void refusesAmbiguousHighestSemverMatches() {
        Claim provider = providerWithOffers(
                new Offer("answer.search", "one", ConsumerAccess.ANY, null, Stability.STABLE, "1.0.0", false, null),
                new Offer("answer.search", "two", ConsumerAccess.ANY, null, Stability.STABLE, "1.0.0", false, null));

        ResolutionResult result = new CapabilityResolver().resolve(new ResolutionRequest(
                "answer.search", null, new SemverRange(">=1.0.0"), URI.create("urn:consumer"), List.of(provider)));

        assertThat(result.resolved()).isFalse();
        assertThat(result.reason()).isEqualTo(ErrorCode.MULTIPLE_MATCHES.name());
    }

    private Claim providerWithOffers(Offer... offers) {
        Claim base = Fixtures.validProviderClaim();
        return new Claim(base.identity(), base.domain(), base.refusals(), base.dependencies(), List.of(offers),
                base.conflictResolution(), base.negotiationSurface(), base.integrationPorts(), base.faults(),
                new ClaimMetadata("0.2.0", base.identity().version(), Instant.EPOCH, Map.of()));
    }
}
