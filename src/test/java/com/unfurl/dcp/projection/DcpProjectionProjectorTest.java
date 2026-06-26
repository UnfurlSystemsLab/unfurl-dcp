package com.unfurl.dcp.projection;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimMetadata;
import com.unfurl.dcp.claim.ComponentKind;
import com.unfurl.dcp.claim.ConsumerAccess;
import com.unfurl.dcp.claim.Dependencies;
import com.unfurl.dcp.claim.Identity;
import com.unfurl.dcp.claim.IntegrationPorts;
import com.unfurl.dcp.claim.InterfaceKind;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.OfferInterface;
import com.unfurl.dcp.claim.Stability;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DcpProjectionProjectorTest {
    @Test
    void projectsRecursiveClaimSubtreeWithDescendants() {
        Claim city = claim("urn:city", "Smart City", ComponentKind.INFRASTRUCTURE, "CITY", List.of("urn:colony"), "city.run");
        Claim colony = claim("urn:colony", "Smart Colony", ComponentKind.COMPONENT, "COLONY", List.of("urn:home"), "colony.run");
        Claim home = claim("urn:home", "Smart Home", ComponentKind.COMPONENT, "HOME", List.of("urn:sensor"), "home.run");
        Claim sensor = claim("urn:sensor", "Sensor", ComponentKind.COMPONENT, "LEAF", List.of(), "sensor.read");

        DcpProjection projection = new DcpProjectionProjector().project(new DcpProjectionRequest(
                city,
                Map.of(
                        uri("urn:city"), city,
                        uri("urn:colony"), colony,
                        uri("urn:home"), home,
                        uri("urn:sensor"), sensor),
                uri("urn:home"),
                8,
                20));

        assertThat(projection.rootClaimUri()).isEqualTo(uri("urn:city"));
        assertThat(projection.focusClaimUri()).isEqualTo(uri("urn:home"));
        assertThat(projection.nodes()).extracting(DcpProjectionNode::claimUri)
                .containsExactly(uri("urn:city"), uri("urn:colony"), uri("urn:home"), uri("urn:sensor"));
        assertThat(node(projection, "urn:city").descendantClaimUris())
                .containsExactly(uri("urn:colony"), uri("urn:home"), uri("urn:sensor"));
        assertThat(node(projection, "urn:home").parentClaimUri()).isEqualTo(uri("urn:colony"));
        assertThat(node(projection, "urn:home").depth()).isEqualTo(2);
        assertThat(node(projection, "urn:sensor").offers()).containsExactly("sensor.read");
        assertThat(projection.edges()).extracting(DcpProjectionEdge::relationship)
                .containsOnly(DcpProjectionProjector.RELATIONSHIP_CONTAINS);
        assertThat(projection.warnings()).isEmpty();
    }

    @Test
    void guardsCyclesAndKeepsLoadedSubtree() {
        Claim root = claim("urn:root", "Root", ComponentKind.INFRASTRUCTURE, "ROOT", List.of("urn:child"), "root.run");
        Claim child = claim("urn:child", "Child", ComponentKind.COMPONENT, "AGGREGATE", List.of("urn:root"), "child.run");

        DcpProjection projection = new DcpProjectionProjector().project(new DcpProjectionRequest(
                root,
                Map.of(uri("urn:root"), root, uri("urn:child"), child),
                null,
                8,
                20));

        assertThat(projection.nodes()).extracting(DcpProjectionNode::claimUri)
                .containsExactly(uri("urn:root"), uri("urn:child"));
        assertThat(projection.warnings()).anyMatch(warning -> warning.contains("cycle skipped"));
    }

    @Test
    void reportsMissingReferencesAndUnknownFocus() {
        Claim root = claim("urn:root", "Root", ComponentKind.INFRASTRUCTURE, "ROOT", List.of("urn:missing"), "root.run");

        DcpProjection projection = new DcpProjectionProjector().project(new DcpProjectionRequest(
                root,
                Map.of(uri("urn:root"), root),
                uri("urn:missing"),
                8,
                20));

        assertThat(projection.focusClaimUri()).isEqualTo(uri("urn:root"));
        assertThat(projection.warnings())
                .anyMatch(warning -> warning.contains("contained claim is not loaded"))
                .anyMatch(warning -> warning.contains("focus claim is not present"));
    }

    @Test
    void appliesDepthCap() {
        Claim root = claim("urn:root", "Root", ComponentKind.INFRASTRUCTURE, "ROOT", List.of("urn:child"), "root.run");
        Claim child = claim("urn:child", "Child", ComponentKind.COMPONENT, "AGGREGATE", List.of("urn:grandchild"), "child.run");
        Claim grandchild = claim("urn:grandchild", "Grandchild", ComponentKind.COMPONENT, "LEAF", List.of(), "grandchild.run");

        DcpProjection projection = new DcpProjectionProjector().project(new DcpProjectionRequest(
                root,
                Map.of(uri("urn:root"), root, uri("urn:child"), child, uri("urn:grandchild"), grandchild),
                null,
                1,
                20));

        assertThat(projection.nodes()).extracting(DcpProjectionNode::claimUri)
                .containsExactly(uri("urn:root"), uri("urn:child"));
        assertThat(projection.warnings()).anyMatch(warning -> warning.contains("projection depth cap reached"));
    }

    private static DcpProjectionNode node(DcpProjection projection, String uri) {
        return projection.nodes().stream()
                .filter(node -> node.claimUri().equals(uri(uri)))
                .findFirst()
                .orElseThrow();
    }

    private static Claim claim(
            String uri,
            String name,
            ComponentKind kind,
            String level,
            List<String> childUris,
            String offer
    ) {
        return new Claim(
                new Identity(uri(uri), name, kind, "1.0.0", "Unfurl", uri("urn:publisher")),
                null,
                List.of(),
                new Dependencies(List.of()),
                List.of(new Offer(offer, offer, ConsumerAccess.ANY,
                        new OfferInterface(InterfaceKind.IN_PROCESS, Map.of()),
                        Stability.STABLE, "1.0.0", false, null)),
                null,
                null,
                new IntegrationPorts(Map.of()),
                new ClaimMetadata("0.2.0", "1.0.0", Instant.EPOCH, Map.of(
                        "level", level,
                        DcpProjectionProjector.EXT_CONTAINS, childUris)));
    }

    private static URI uri(String value) {
        return URI.create(value);
    }
}
