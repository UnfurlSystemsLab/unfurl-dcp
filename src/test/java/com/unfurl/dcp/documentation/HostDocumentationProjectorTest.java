package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimHash;
import com.unfurl.dcp.claim.ClaimKey;
import com.unfurl.dcp.host.ArtifactPin;
import com.unfurl.dcp.host.HostReport;
import com.unfurl.dcp.host.ProviderArtifactPin;
import com.unfurl.dcp.testing.DocumentationWorld;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.unfurl.dcp.testing.DocumentationWorld.HOST;
import static com.unfurl.dcp.testing.DocumentationWorld.HOST_ROOT;
import static com.unfurl.dcp.testing.DocumentationWorld.T0;
import static com.unfurl.dcp.testing.DocumentationWorld.TOOL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the host view (DCP HLD-C2 G.2): a host documents itself and the components it hosts from its own verified state;
 * hosted documentation comes from the admitted claims; matching is one-way, so retained evidence after revocation is
 * never an error; and only currently registered capabilities are rendered.
 */
class HostDocumentationProjectorTest {
    private final HostDocumentationProjector projector = new HostDocumentationProjector();

    /** The host's own and its hosted capabilities are documented; hosted ones come from the admitted claim. */
    @Test
    void documentsTheHostAndItsHostedComponents() {
        DocumentationWorld world = DocumentationWorld.standard();
        ProjectedDocumentation view = projector.project(inputs(world, world.report(HOST, "urn:c:agent", "urn:c:tool"),
                List.of(admitted(world, tool(world)))), T0);

        DocumentedComponent host = view.documentation().components().get(0);
        assertThat(host.capabilities()).extracting(DocumentedCapability::capability).containsExactly("agent.run", "tool.lookup");
        assertThat(view.documentation().source().rootContract()).isNull();
        ObjectNode doc = new OpenApiDocumentRenderer().render(view.documentation(), view.artifacts()).get(HOST);
        assertThat(doc.path("paths").has("/dcp/agent.run")).isTrue();
        assertThat(doc.at("/x-dcp-hosted-capabilities/0/description").asText()).isEqualTo("Tool tool.lookup");
    }

    /** A component whose last contract was revoked is retained evidence: not rendered, and not an error. */
    @Test
    void revokedHostedComponentIsRetainedNotRendered() {
        DocumentationWorld world = DocumentationWorld.standard();
        ProjectedDocumentation view = projector.project(inputs(world, world.report(HOST, "urn:c:agent"),
                List.of(admitted(world, tool(world)))), T0);

        assertThat(view.documentation().components().get(0).capabilities())
                .extracting(DocumentedCapability::capability).containsExactly("agent.run");
    }

    /** Retained evidence with a broken artifact does not fail the view, because it is not rendered. */
    @Test
    void retainedEvidenceIsNotValidated() {
        DocumentationWorld world = DocumentationWorld.standard();
        DocumentationArtifactStore broken = ref -> { throw new IllegalStateException("must not be read"); };
        HostedComponentDocumentation revoked = new HostedComponentDocumentation(tool(world), pin(tool(world)), broken);

        assertThat(projector.project(inputs(world, world.report(HOST, "urn:c:agent"), List.of(revoked)), T0)
                .documentation().components()).hasSize(1);
    }

    /** A hosted registration without admitted evidence, or with disagreeing hashes, is refused. */
    @Test
    void mismatchedEvidenceIsRefused() {
        DocumentationWorld world = DocumentationWorld.standard();
        RegistrationReport both = world.report(HOST, "urn:c:agent", "urn:c:tool");

        assertThatThrownBy(() -> projector.project(inputs(world, both, List.of()), T0))
                .hasMessageContaining(DocumentationException.HOST_VIEW_INPUT_MISMATCH);
        HostedComponentDocumentation wrongPin = new HostedComponentDocumentation(tool(world),
                new ProviderArtifactPin(new ArtifactPin("example:tool:0.2.0", "a".repeat(64)), "d".repeat(64), List.of()),
                world.store());
        assertThatThrownBy(() -> projector.project(inputs(world, both, List.of(wrongPin)), T0))
                .hasMessageContaining(DocumentationException.HOST_VIEW_INPUT_MISMATCH);
    }

    /** A private registered capability is reported, not documented. */
    @Test
    void privateCapabilitiesAreReportedNotDocumented() {
        DocumentationWorld world = DocumentationWorld.standard();
        world.policy = new VisibilityPolicy(Visibility.INTERNAL, java.util.Map.of("tool.lookup", Visibility.PRIVATE), java.util.Map.of());
        ProjectedDocumentation view = projector.project(inputs(world, world.report(HOST, "urn:c:agent", "urn:c:tool"),
                List.of(admitted(world, tool(world)))), T0);

        assertThat(view.documentation().report().entries()).anySatisfy(e -> {
            assertThat(e.capability()).isEqualTo("tool.lookup");
            assertThat(e.reason()).isEqualTo(ReasonCode.POLICY_PRIVATE);
        });
    }

    /** A registered offer without documentation is a gap, naming the capability; a schema is never inferred. */
    @Test
    void undocumentedRegisteredOfferIsAGap() {
        DocumentationWorld world = DocumentationWorld.standard();
        Claim host = world.claims.get(new ClaimKey(HOST, "1.0.0"));
        com.unfurl.dcp.claim.Offer run = host.offers().get(0);
        world.put(com.unfurl.dcp.testing.DocumentationFixtures.withOffers(host, List.of(new com.unfurl.dcp.claim.Offer(
                run.capability(), run.description(), run.consumerAccess(), run.offerInterface(), run.stability(), run.version(),
                false, null))));

        assertThatThrownBy(() -> projector.project(inputs(world, world.report(HOST, "urn:c:agent"), List.of()), T0))
                .isInstanceOf(DocumentationGap.DocumentationGapException.class).hasMessageContaining("agent.run");
    }

    // -------------------------------------------------------------------------------------------------------- helpers

    private static Claim tool(DocumentationWorld world) {
        return world.claims.get(new ClaimKey(TOOL, "0.2.0"));
    }

    private static ProviderArtifactPin pin(Claim claim) {
        return new ProviderArtifactPin(new ArtifactPin("example:tool:0.2.0", "a".repeat(64)), ClaimHash.canonical(claim), List.of());
    }

    /** Admission evidence as the in-process host records it: claim, pin, and resources over the jar snapshot. */
    private static HostedComponentDocumentation admitted(DocumentationWorld world, Claim claim) {
        DocumentationArtifactStore store = world.store();
        return HostedComponentDocumentation.from(new HostReport.AdmittedComponent(claim, pin(claim), path -> {
            Optional<ArtifactPinnedFile> file = claim.offers().stream().flatMap(o -> o.documentation().operations().stream())
                    .flatMap(op -> java.util.stream.Stream.of(op.requestSchema(), op.responseSchema()))
                    .filter(ref -> ref.path().equals(path)).findFirst().map(ref -> new ArtifactPinnedFile(store.read(ref)));
            return file.map(ArtifactPinnedFile::bytes);
        }));
    }

    private record ArtifactPinnedFile(byte[] bytes) { }

    private static HostDocumentationInputs inputs(DocumentationWorld world, RegistrationReport local,
                                                  List<HostedComponentDocumentation> hosted) {
        Claim host = world.claims.get(new ClaimKey(HOST, "1.0.0"));
        return new HostDocumentationInputs(host, world.store(), local, hosted, IdentityMode.ABSENT, Set.of(), world.policy,
                HOST_ROOT, DocumentationWorld.env(), java.net.URI.create("urn:docs:host"), "host@1");
    }
}
