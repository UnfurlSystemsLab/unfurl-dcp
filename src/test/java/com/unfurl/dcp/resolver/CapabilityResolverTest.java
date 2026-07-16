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

    @Test
    void matchesRequiredExecutionModeInOfferDetails() {
        Claim provider = providerWithOffers(new Offer("agent.run", "run agent", ConsumerAccess.ANY,
                new OfferInterface(InterfaceKind.IN_PROCESS, Map.of(
                        "operation", "start",
                        "execution_modes", List.of("simple", "harness"))),
                Stability.STABLE, "1.0.0", true, "metered=true; unit=tokens"));

        ResolutionResult result = new CapabilityResolver().resolve(new ResolutionRequest(
                "agent.run",
                null,
                new SemverRange(">=1.0.0"),
                URI.create("urn:consumer"),
                Map.of(),
                Map.of("execution_modes", List.of("harness")),
                List.of(provider)));

        assertThat(result.resolved()).isTrue();
        assertThat(result.providerCapability()).isEqualTo("agent.run");
    }

    @Test
    void refusesWhenRequiredExecutionModeIsMissing() {
        Claim provider = providerWithOffers(new Offer("agent.run", "run agent", ConsumerAccess.ANY,
                new OfferInterface(InterfaceKind.IN_PROCESS, Map.of(
                        "operation", "start",
                        "execution_modes", List.of("simple"))),
                Stability.STABLE, "1.0.0", true, "metered=true; unit=tokens"));

        ResolutionResult result = new CapabilityResolver().resolve(new ResolutionRequest(
                "agent.run",
                null,
                new SemverRange(">=1.0.0"),
                URI.create("urn:consumer"),
                Map.of(),
                Map.of("execution_modes", List.of("harness")),
                List.of(provider)));

        assertThat(result.resolved()).isFalse();
        assertThat(result.reason()).isEqualTo(ErrorCode.NO_MATCHING_CONTRACT.name());
    }

    @Test
    void matchesNestedOfferDetailSubset() {
        Claim provider = providerWithOffers(new Offer("agent.run", "run agent", ConsumerAccess.ANY,
                new OfferInterface(InterfaceKind.IN_PROCESS, Map.of(
                        "mode_policies", Map.of(
                                "harness", Map.of(
                                        "max_turns_default", 4,
                                        "max_turns_max", 16,
                                        "resume", "in_memory")))),
                Stability.STABLE, "1.0.0", true, "metered=true; unit=tokens"));

        ResolutionResult result = new CapabilityResolver().resolve(new ResolutionRequest(
                "agent.run",
                null,
                new SemverRange(">=1.0.0"),
                URI.create("urn:consumer"),
                Map.of(),
                Map.of("mode_policies", Map.of("harness", Map.of("max_turns_max", 16))),
                List.of(provider)));

        assertThat(result.resolved()).isTrue();
    }

    private Claim providerWithOffers(Offer... offers) {
        Claim base = Fixtures.validProviderClaim();
        return new Claim(base.identity(), base.domain(), base.refusals(), base.dependencies(), List.of(offers),
                base.conflictResolution(), base.negotiationSurface(), base.integrationPorts(), base.faults(),
                new ClaimMetadata("0.2.0", base.identity().version(), Instant.EPOCH, Map.of()));
    }
}
