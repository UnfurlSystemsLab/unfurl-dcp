package com.unfurl.dcp.claim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.projection.DcpProjection;
import com.unfurl.dcp.projection.DcpProjectionEdge;
import com.unfurl.dcp.projection.DcpProjectionProjector;
import com.unfurl.dcp.projection.DcpProjectionRequest;
import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aggregate claims (DCP HLD-C2 A.14): {@code child_claims} names the components an aggregate is built from. Each entry
 * names a child by {@code claim_uri} and always writes {@code required}; a claim declares its children in one place; a
 * claim without children keeps its hash; and the projection draws the children under the aggregate.
 */
class ChildClaimsTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ClaimValidator validator = new ClaimValidator();

    @Test
    void acceptsAnAggregateWithRequiredAndOptionalChildren() {
        Claim aggregate = withChildren(Fixtures.validProviderClaim(), List.of(
                new ChildClaim(URI.create("urn:unfurl:engine"), true, "runs agents"),
                new ChildClaim(URI.create("urn:unfurl:rag"), false, null)));

        assertThat(childDiagnostics(aggregate)).isEmpty();
        assertThat(aggregate.childClaims()).extracting(ChildClaim::isRequired).containsExactly(true, false);
    }

    @Test
    void refusesAMissingUriADuplicateItselfAndAnUnwrittenRequired() {
        URI own = Fixtures.validProviderClaim().identity().uri();
        Claim aggregate = withChildren(Fixtures.validProviderClaim(), List.of(
                new ChildClaim(null, true, null),
                new ChildClaim(URI.create("urn:unfurl:engine"), true, null),
                new ChildClaim(URI.create("urn:unfurl:engine"), false, null),
                new ChildClaim(own, true, null),
                new ChildClaim(URI.create("urn:unfurl:rag"), null, null)));

        assertThat(childDiagnostics(aggregate)).extracting(Diagnostic::fieldPath, Diagnostic::message).containsExactly(
                org.assertj.core.groups.Tuple.tuple("child_claims[0].claim_uri", "claim_uri is required"),
                org.assertj.core.groups.Tuple.tuple("child_claims[2].claim_uri", "child urn:unfurl:engine is declared twice"),
                org.assertj.core.groups.Tuple.tuple("child_claims[3].claim_uri", "an aggregate cannot contain itself: " + own),
                org.assertj.core.groups.Tuple.tuple("child_claims[4].required", "required must be written: there is no default"));
    }

    @Test
    void refusesChildrenDeclaredBothInChildClaimsAndInTheContainmentBridge() {
        Claim base = Fixtures.validProviderClaim();
        Claim bridged = new Claim(base.identity(), base.domain(), base.refusals(), base.dependencies(), base.offers(),
                base.conflictResolution(), base.negotiationSurface(), base.integrationPorts(), base.faults(),
                new ClaimMetadata(base.metadata().dcpVersion(), base.metadata().claimVersion(), base.metadata().createdAt(),
                        Map.of("containsClaimUris", List.of("urn:unfurl:engine"))),
                List.of(), List.of(new ChildClaim(URI.create("urn:unfurl:engine"), true, null)));

        assertThat(childDiagnostics(bridged)).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.fieldPath()).isEqualTo("child_claims");
            assertThat(diagnostic.message()).contains("not both");
        });
    }

    @Test
    void keepsTheHashOfAClaimWithoutChildrenAndHashesTheChildren() {
        Claim plain = Fixtures.validProviderClaim();
        assertThat(ClaimHash.canonical(withChildren(plain, List.of()))).isEqualTo(ClaimHash.canonical(plain));
        Claim aggregate = withChildren(plain, List.of(new ChildClaim(URI.create("urn:unfurl:engine"), true, null)));
        assertThat(ClaimHash.canonical(aggregate)).isNotEqualTo(ClaimHash.canonical(plain));
        Claim optional = withChildren(plain, List.of(new ChildClaim(URI.create("urn:unfurl:engine"), false, null)));
        assertThat(ClaimHash.canonical(optional)).as("required is part of the claim").isNotEqualTo(ClaimHash.canonical(aggregate));
    }

    @Test
    void readsChildClaimsFromTheSnakeCaseWire() throws Exception {
        ObjectNode claim = YAML.valueToTree(Fixtures.validProviderClaim());
        claim.set("child_claims", YAML.readTree("""
                - claim_uri: urn:unfurl:engine
                  required: true
                  description: runs agents
                - claim_uri: urn:unfurl:rag
                  required: false
                """));

        Claim read = YAML.treeToValue(claim, Claim.class);

        assertThat(read.childClaims()).containsExactly(
                new ChildClaim(URI.create("urn:unfurl:engine"), true, "runs agents"),
                new ChildClaim(URI.create("urn:unfurl:rag"), false, null));
    }

    @Test
    void projectsTheChildrenUnderTheAggregate() {
        Claim engine = Fixtures.validProviderClaim();
        URI engineUri = engine.identity().uri();
        Claim base = Fixtures.validProviderClaim();
        Claim aggregate = new Claim(new Identity(URI.create("urn:unfurl:aggregate"), "Aggregate", base.identity().kind(),
                base.identity().version(), base.identity().publisher(), base.identity().publisherUri()), base.domain(),
                base.refusals(), base.dependencies(), base.offers(), base.conflictResolution(), base.negotiationSurface(),
                base.integrationPorts(), base.faults(), base.metadata(), List.of(),
                List.of(new ChildClaim(engineUri, true, null)));

        DcpProjection projection = new DcpProjectionProjector().project(new DcpProjectionRequest(aggregate,
                Map.of(aggregate.identity().uri(), aggregate, engineUri, engine), aggregate.identity().uri(), 8, 20));

        assertThat(projection.edges()).containsExactly(
                new DcpProjectionEdge(aggregate.identity().uri(), engineUri, DcpProjectionProjector.RELATIONSHIP_CONTAINS));
    }

    /** The claim with {@code children} as its {@code child_claims}. */
    private static Claim withChildren(Claim claim, List<ChildClaim> children) {
        return new Claim(claim.identity(), claim.domain(), claim.refusals(), claim.dependencies(), claim.offers(),
                claim.conflictResolution(), claim.negotiationSurface(), claim.integrationPorts(), claim.faults(),
                claim.metadata(), claim.apiDocuments(), children);
    }

    /** The validator's diagnostics about {@code child_claims}. */
    private List<Diagnostic> childDiagnostics(Claim claim) {
        return validator.validate(claim).diagnostics().stream()
                .filter(diagnostic -> diagnostic.code() == ErrorCode.CLAIM_CHILD_CLAIM_INVALID)
                .toList();
    }
}
