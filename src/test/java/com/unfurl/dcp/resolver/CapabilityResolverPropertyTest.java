package com.unfurl.dcp.resolver;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimMetadata;
import com.unfurl.dcp.claim.ComponentKind;
import com.unfurl.dcp.claim.Concern;
import com.unfurl.dcp.claim.ConflictResolution;
import com.unfurl.dcp.claim.ConsumerAccess;
import com.unfurl.dcp.claim.Dependencies;
import com.unfurl.dcp.claim.DomainAssertion;
import com.unfurl.dcp.claim.Identity;
import com.unfurl.dcp.claim.IntegrationPorts;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.Refusal;
import com.unfurl.dcp.claim.Stability;
import com.unfurl.dcp.versioning.SemverRange;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityResolverPropertyTest {
    @Property(tries = 20)
    void shufflingCandidatesKeepsSameHighestMatch(@ForAll @IntRange(min = 1, max = 1000) int seed) {
        List<Claim> claims = new ArrayList<>(List.of(
                provider("urn:p1", "1.0.0"),
                provider("urn:p2", "1.2.0"),
                provider("urn:p3", "2.0.0")));
        ResolutionRequest original = new ResolutionRequest("answer.search", null, new SemverRange(">=1.0.0"), URI.create("urn:consumer"), claims);
        Collections.shuffle(claims, new Random(seed));
        ResolutionRequest shuffled = new ResolutionRequest("answer.search", null, new SemverRange(">=1.0.0"), URI.create("urn:consumer"), claims);

        CapabilityResolver resolver = new CapabilityResolver();

        assertThat(resolver.resolve(shuffled)).isEqualTo(resolver.resolve(original));
    }

    private Claim provider(String uri, String offerVersion) {
        return new Claim(
                new Identity(URI.create(uri), uri, ComponentKind.COMPONENT, "1.0.0", "Unfurl", URI.create("urn:publisher")),
                new DomainAssertion("provider", List.of(new Concern("answers", "answers", null, List.of(), List.of())), List.of("boundary")),
                List.of(new Refusal("billing", "billing belongs elsewhere", "urn:billing")),
                new Dependencies(List.of()),
                List.of(new Offer("answer.search", "Search", ConsumerAccess.ANY, null, Stability.STABLE, offerVersion, false, null)),
                new ConflictResolution(List.of(), List.of(), false),
                null,
                new IntegrationPorts(Map.of()),
                com.unfurl.dcp.fault.FaultPolicy.empty(),
                new ClaimMetadata("0.2.0", "1.0.0", Instant.EPOCH, Map.of()));
    }
}
