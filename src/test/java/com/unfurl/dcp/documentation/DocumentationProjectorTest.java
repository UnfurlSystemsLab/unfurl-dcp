package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimHash;
import com.unfurl.dcp.claim.ClaimKey;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.documentation.DocumentationGap.DocumentationGapException;
import com.unfurl.dcp.runtimebinding.ConfigRef;
import com.unfurl.dcp.runtimebinding.DeploymentKind;
import com.unfurl.dcp.runtimebinding.ProviderInstance;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.runtimebinding.RuntimePolicy;
import com.unfurl.dcp.testing.DocumentationFixtures;
import com.unfurl.dcp.testing.DocumentationWorld;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.unfurl.dcp.testing.DocumentationWorld.FLOW;
import static com.unfurl.dcp.testing.DocumentationWorld.FLOW_ROOT;
import static com.unfurl.dcp.testing.DocumentationWorld.HOST;
import static com.unfurl.dcp.testing.DocumentationWorld.HOST_ROOT;
import static com.unfurl.dcp.testing.DocumentationWorld.T0;
import static com.unfurl.dcp.testing.DocumentationWorld.TOOL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the documentation projector (DCP HLD-C2 G.1-G.4): one test per reason code, the separate host and provider
 * checks (hosted components verified against their own pins), the static refusals, and identity.
 */
class DocumentationProjectorTest {
    private final DocumentationProjector projector = new DocumentationProjector();

    /** A healthy world documents every HTTP and hosted capability, and observes exactly the bound roots. */
    @Test
    void healthyWorldDocumentsEverything() {
        DocumentationWorld world = DocumentationWorld.standard();
        DocumentationPlan plan = projector.plan(world.inputs());

        assertThat(plan.serviceRoots()).containsExactly(FLOW_ROOT, HOST_ROOT);
        CapabilityDocumentation docs = plan.project(world.healthy(), T0);

        assertThat(reasons(docs)).containsEntry("urn:c:agent", ReasonCode.ACTIVE).containsEntry("urn:c:tool", ReasonCode.ACTIVE)
                .containsEntry("urn:c:flow", ReasonCode.ACTIVE);
        assertThat(docs.components()).extracting(DocumentedComponent::componentUri).containsExactly(FLOW, HOST);
        DocumentedComponent host = component(docs, HOST);
        assertThat(host.serviceRoot()).isEqualTo(HOST_ROOT);
        assertThat(host.capabilities()).extracting(DocumentedCapability::capability).containsExactly("agent.run", "tool.lookup");
        DocumentedCapability tool = host.capabilities().get(1);
        assertThat(tool.surface()).isEqualTo(EntrySurface.HOSTED_IN_PROCESS);
        assertThat(tool.hostedBy()).isEqualTo(HOST);
        assertThat(tool.providerComponentUri()).isEqualTo(TOOL);
        assertThat(tool.description()).isEqualTo("Tool tool.lookup");
        assertThat(tool.faults()).isNotEmpty();
        assertThat(docs.identity()).isEqualTo(IdentityMode.ABSENT);
        assertThat(docs.source().registeredCapabilities()).containsExactly("agent.run", "tool.lookup", "workflow.execute");
        assertThat(docs.source().rootContract()).isEqualTo(URI.create("urn:c:root"));
    }

    /** A root never observed is PROVIDER_UNREACHABLE, never active, and documents nothing. */
    @Test
    void unobservedRootsAreNeverActive() {
        CapabilityDocumentation docs = projector.plan(DocumentationWorld.standard().inputs()).project(Map.of(), T0);

        assertThat(docs.report().entries()).allSatisfy(entry -> {
            assertThat(entry.reason()).isEqualTo(ReasonCode.PROVIDER_UNREACHABLE);
            assertThat(entry.detail()).isEqualTo(DocumentationPlan.NOT_YET_OBSERVED);
        });
        assertThat(docs.components()).isEmpty();
    }

    /** Transport outcomes become PROVIDER_UNREACHABLE and REGISTRATION_UNAUTHORIZED, for every entry on the root. */
    @Test
    void transportOutcomesAreReported() {
        DocumentationWorld world = DocumentationWorld.standard();
        Map<URI, RegistrationObservation> observations = new HashMap<>(world.healthy());
        observations.put(HOST_ROOT, new RegistrationObservation.Unreachable("connection refused", T0));
        observations.put(FLOW_ROOT, new RegistrationObservation.Unauthorized(403, T0));

        Map<String, ReasonCode> reasons = reasons(projector.plan(world.inputs()).project(observations, T0));

        assertThat(reasons).containsEntry("urn:c:agent", ReasonCode.PROVIDER_UNREACHABLE)
                .containsEntry("urn:c:tool", ReasonCode.PROVIDER_UNREACHABLE)
                .containsEntry("urn:c:flow", ReasonCode.REGISTRATION_UNAUTHORIZED);
    }

    /** A host running a different build fails the host check for every entry on its root, hosted ones included. */
    @Test
    void wrongHostFailsTheHostCheck() {
        DocumentationWorld world = DocumentationWorld.standard();
        RegistrationReport good = world.report(HOST, "urn:c:agent", "urn:c:tool");
        RegistrationReport otherBuild = new RegistrationReport(good.componentUri(), good.componentVersion(), "b".repeat(64), T0,
                good.registrations().stream().map(r -> r.surface() == EntrySurface.HTTP
                        ? new Registration(r.contractId(), r.contractVersion(), r.capability(), r.providerClaimUri(), r.providerClaimVersion(),
                        "b".repeat(64), r.surface(), r.path()) : r).toList(), List.of());
        Map<URI, RegistrationObservation> observations = new HashMap<>(world.healthy());
        observations.put(HOST_ROOT, new RegistrationObservation.Reported(otherBuild, T0));

        Map<String, ReasonCode> reasons = reasons(projector.plan(world.inputs()).project(observations, T0));

        assertThat(reasons).containsEntry("urn:c:agent", ReasonCode.HOST_CLAIM_MISMATCH)
                .containsEntry("urn:c:tool", ReasonCode.HOST_CLAIM_MISMATCH).containsEntry("urn:c:flow", ReasonCode.ACTIVE);
    }

    /** A hosted component admitted from a different build fails only its own provider check. */
    @Test
    void wrongHostedBuildFailsOnlyItsProviderCheck() {
        DocumentationWorld world = DocumentationWorld.standard();
        RegistrationReport good = world.report(HOST, "urn:c:agent", "urn:c:tool");
        RegistrationReport report = new RegistrationReport(good.componentUri(), good.componentVersion(), good.claimHash(), T0,
                good.registrations().stream().map(r -> r.surface() == EntrySurface.HOSTED_IN_PROCESS
                        ? new Registration(r.contractId(), r.contractVersion(), r.capability(), r.providerClaimUri(), r.providerClaimVersion(),
                        "c".repeat(64), r.surface(), r.path()) : r).toList(), List.of());
        Map<URI, RegistrationObservation> observations = new HashMap<>(world.healthy());
        observations.put(HOST_ROOT, new RegistrationObservation.Reported(report, T0));

        CapabilityDocumentation docs = projector.plan(world.inputs()).project(observations, T0);

        assertThat(reasons(docs)).containsEntry("urn:c:agent", ReasonCode.ACTIVE).containsEntry("urn:c:tool", ReasonCode.CLAIM_MISMATCH);
        assertThat(component(docs, HOST).capabilities()).extracting(DocumentedCapability::capability).containsExactly("agent.run");
    }

    /** A reachable host that does not list a contract leaves it NOT_REGISTERED. */
    @Test
    void unlistedContractIsNotRegistered() {
        DocumentationWorld world = DocumentationWorld.standard();
        Map<URI, RegistrationObservation> observations = new HashMap<>(world.healthy());
        observations.put(HOST_ROOT, new RegistrationObservation.Reported(world.report(HOST, "urn:c:agent"), T0));

        assertThat(reasons(projector.plan(world.inputs()).project(observations, T0)))
                .containsEntry("urn:c:agent", ReasonCode.ACTIVE).containsEntry("urn:c:tool", ReasonCode.NOT_REGISTERED);
    }

    /** Static reasons: disabled, missing, malformed or wrongly shaped bindings, private policy and unknown hosts. */
    @Test
    void staticReasonsNeedNoObservation() {
        DocumentationWorld world = DocumentationWorld.standard();
        world.replaceBinding("urn:c:flow", b -> withPolicy(b, false));
        DocumentationPlan plan = projector.plan(world.inputs());
        assertThat(reasons(plan.project(world.healthy(), T0))).containsEntry("urn:c:flow", ReasonCode.BINDING_DISABLED);
        assertThat(plan.serviceRoots()).containsExactly(HOST_ROOT);

        DocumentationWorld mismatch = DocumentationWorld.standard();
        mismatch.replaceBinding("urn:c:flow", b -> withEndpoint(b, URI.create("http://flow:8080/contracts/workflow.execute"), null));
        ReportEntry entry = entry(projector.plan(mismatch.inputs()).project(mismatch.healthy(), T0), "urn:c:flow");
        assertThat(entry.reason()).isEqualTo(ReasonCode.ENDPOINT_PATH_MISMATCH);
        assertThat(entry.endpoint()).isEqualTo(URI.create("http://flow:8080/contracts/workflow.execute"));

        DocumentationWorld missing = DocumentationWorld.standard();
        missing.replaceBinding("urn:c:flow", b -> withEndpoint(b, null, null));
        assertThat(reasons(projector.plan(missing.inputs()).project(missing.healthy(), T0))).containsEntry("urn:c:flow", ReasonCode.BINDING_ENDPOINT_MISSING);

        DocumentationWorld unresolved = DocumentationWorld.standard();
        unresolved.replaceBinding("urn:c:flow", b -> withEndpoint(b, null, new ConfigRef("config://acme/flow/endpoint")));
        assertThat(entry(projector.plan(unresolved.inputs()).project(unresolved.healthy(), T0), "urn:c:flow").detail())
                .contains("no configuration is mounted");

        DocumentationWorld unbound = DocumentationWorld.standard();
        unbound.bindings.removeIf(b -> b.contractId().toString().equals("urn:c:flow"));
        assertThat(reasons(projector.plan(unbound.inputs()).project(unbound.healthy(), T0))).containsEntry("urn:c:flow", ReasonCode.BINDING_MISSING);

        DocumentationWorld privateFlow = DocumentationWorld.standard();
        privateFlow.policy = new VisibilityPolicy(Visibility.INTERNAL, Map.of("workflow.execute", Visibility.PRIVATE), Map.of());
        assertThat(reasons(projector.plan(privateFlow.inputs()).project(privateFlow.healthy(), T0))).containsEntry("urn:c:flow", ReasonCode.POLICY_PRIVATE);

        DocumentationWorld orphan = DocumentationWorld.standard();
        orphan.bindings.removeIf(b -> b.contractId().toString().equals("urn:c:agent"));
        assertThat(reasons(projector.plan(orphan.inputs()).project(orphan.healthy(), T0))).containsEntry("urn:c:tool", ReasonCode.HOST_UNKNOWN);
    }

    /** External providers are listed, never probed. */
    @Test
    void externalProvidersAreNotProbed() {
        DocumentationWorld world = DocumentationWorld.standard().withIdentity();
        DocumentationPlan plan = projector.plan(world.inputs());
        ReportEntry identity = entry(plan.project(world.healthy(), T0), "urn:c:identity");

        assertThat(identity.status()).isEqualTo(EntryStatus.NOT_PROBED);
        assertThat(identity.reason()).isEqualTo(ReasonCode.EXTERNAL_SURFACE);
        assertThat(plan.serviceRoots()).containsExactly(FLOW_ROOT, HOST_ROOT);
    }

    /**
     * An external_protocol offer (HLD-C2 A.6, G.1) is external whatever its deployment: an identity provider deployed
     * with the composition as a container and one the customer runs (external SaaS) are both listed with
     * EXTERNAL_SURFACE, never probed, and never a gap, although the offer has no documentation. The same container
     * binding with an ordinary interface kind is HTTP, so it would be a gap: the surface comes from the offer.
     */
    @Test
    void externalProtocolOffersAreExternalInEveryDeployment() {
        for (DeploymentKind kind : List.of(DeploymentKind.CONTAINER, DeploymentKind.EXTERNAL_SAAS)) {
            DocumentationWorld world = DocumentationWorld.standard().withIdentity();
            world.put(identityOffer(world, new com.unfurl.dcp.claim.OfferInterface(
                    com.unfurl.dcp.claim.InterfaceKind.EXTERNAL_PROTOCOL, Map.of("protocol", "oauth2"))));
            world.replaceBinding("urn:c:identity", b -> withDeploymentKind(b, kind));

            assertThat(new CapabilityDocumentationValidator().gaps(world.composition(), world.claims, world.bindings, world.policy))
                    .as(kind.name()).isEmpty();
            DocumentationPlan plan = projector.plan(world.inputs());
            ReportEntry identity = entry(plan.project(world.healthy(), T0), "urn:c:identity");
            assertThat(identity.surface()).as(kind.name()).isEqualTo(EntrySurface.EXTERNAL);
            assertThat(identity.reason()).as(kind.name()).isEqualTo(ReasonCode.EXTERNAL_SURFACE);
            assertThat(identity.status()).as(kind.name()).isEqualTo(EntryStatus.NOT_PROBED);
            assertThat(plan.serviceRoots()).as(kind.name()).containsExactly(FLOW_ROOT, HOST_ROOT);
        }

        DocumentationWorld unloaded = DocumentationWorld.standard().withIdentity();
        unloaded.put(identityOffer(unloaded, new com.unfurl.dcp.claim.OfferInterface(
                com.unfurl.dcp.claim.InterfaceKind.EXTERNAL_PROTOCOL, Map.of("protocol", "oauth2"))));
        assertThat(new CapabilityDocumentationValidator().gaps(unloaded.children, unloaded.claims, unloaded.bindings,
                unloaded.policy)).as("the child-contract overload applies the same rule").isEmpty();

        DocumentationWorld ordinary = DocumentationWorld.standard().withIdentity();
        ordinary.put(identityOffer(ordinary, ordinary.claims.get(new ClaimKey(FLOW, "1.0.0")).offers().get(0).offerInterface()));
        ordinary.replaceBinding("urn:c:identity", b -> withDeploymentKind(b, DeploymentKind.CONTAINER));
        assertThat(new CapabilityDocumentationValidator().gaps(ordinary.composition(), ordinary.claims, ordinary.bindings,
                ordinary.policy)).extracting(DocumentationGap::capability).containsExactly("identity-context");
        assertThat(new CapabilityDocumentationValidator().gaps(ordinary.children, ordinary.claims, ordinary.bindings,
                ordinary.policy)).extracting(DocumentationGap::capability).containsExactly("identity-context");
    }

    /** The world's identity claim with its one offer replaced by an undocumented one of the given interface. */
    private static Claim identityOffer(DocumentationWorld world, com.unfurl.dcp.claim.OfferInterface offerInterface) {
        Claim idp = world.claims.get(new ClaimKey(DocumentationWorld.IDP, "1.0.0"));
        Offer offer = idp.offers().get(0);
        return DocumentationFixtures.withOffers(idp, List.of(new Offer(offer.capability(), offer.description(),
                offer.consumerAccess(), offerInterface, offer.stability(), offer.version(), false, null)));
    }

    /** A binding with its provider's deployment kind replaced; it keeps no base URL, as an identity binding has none. */
    private static RuntimeBinding withDeploymentKind(RuntimeBinding b, DeploymentKind kind) {
        ProviderInstance p = b.providerInstance();
        return new RuntimeBinding(b.bindingId(), b.contractId(), b.contractVersion(), b.targetEnvironment(),
                new ProviderInstance(p.componentUri(), p.componentVersion(), p.instanceName(), kind, null, p.baseUrlRef(),
                        p.credentialsRef()),
                b.consumerInstance(), b.runtimePolicy(), b.configuration(), b.deploymentControls(), b.lifecycle(), b.metadata());
    }

    /** Identity comes from the verified set: protected components are marked; without identity nothing is. */
    @Test
    void identityComesFromTheVerifiedSet() {
        DocumentationWorld world = DocumentationWorld.standard().withIdentity();
        CapabilityDocumentation docs = projector.plan(world.inputs()).project(world.healthy(), T0);

        assertThat(docs.identity()).isEqualTo(IdentityMode.IN_CONTRACT);
        assertThat(docs.components()).allSatisfy(component -> assertThat(component.protectedComponent()).isTrue());

        CapabilityDocumentation absent = projector.plan(DocumentationWorld.standard().inputs()).project(world.healthy(), T0);
        assertThat(absent.components()).allSatisfy(component -> assertThat(component.protectedComponent()).isFalse());
    }

    /** An undocumented, documentable offer is a gap that names every gap; a private one is not a gap. */
    @Test
    void gapsRefuseTheProjection() {
        DocumentationWorld world = DocumentationWorld.standard();
        Claim flow = world.claims.get(new ClaimKey(FLOW, "1.0.0"));
        Offer offer = flow.offers().get(0);
        world.put(DocumentationFixtures.withOffers(flow, List.of(new Offer(offer.capability(), offer.description(), offer.consumerAccess(),
                offer.offerInterface(), offer.stability(), offer.version(), false, null))));

        assertThatThrownBy(() -> projector.plan(world.inputs())).isInstanceOf(DocumentationGapException.class)
                .satisfies(gap -> assertThat(((DocumentationGapException) gap).gaps())
                        .extracting(DocumentationGap::capability).containsExactly("workflow.execute"));

        world.policy = new VisibilityPolicy(Visibility.INTERNAL, Map.of("workflow.execute", Visibility.PRIVATE), Map.of());
        assertThat(projector.plan(world.inputs()).serviceRoots()).containsExactly(HOST_ROOT);
    }

    /** A bundled claim that does not match its pin, or a shipped file that changed, refuses the projection. */
    @Test
    void pinsAndArtifactsAreVerified() {
        DocumentationWorld world = DocumentationWorld.standard();
        Claim flow = world.claims.get(new ClaimKey(FLOW, "1.0.0"));
        world.claims.put(ClaimKey.of(flow), DocumentationFixtures.withApiDocuments(flow, List.of()).equals(flow)
                ? new Claim(flow.identity(), flow.domain(), flow.refusals(), flow.dependencies(), flow.offers(), flow.conflictResolution(),
                null, flow.integrationPorts(), flow.faults(), new com.unfurl.dcp.claim.ClaimMetadata("0.2.1", "1.0.0", null, Map.of()))
                : flow);
        assertThatThrownBy(() -> projector.plan(world.inputs())).isInstanceOf(DocumentationException.class)
                .satisfies(e -> assertThat(((DocumentationException) e).code()).isEqualTo(DocumentationException.CLAIM_PIN_MISMATCH));

        DocumentationWorld tampered = DocumentationWorld.standard();
        tampered.files.replaceAll((sha, bytes) -> "{\"type\":\"object\"}".getBytes());
        assertThatThrownBy(() -> projector.plan(tampered.inputs()))
                .satisfies(e -> assertThat(((DocumentationException) e).code()).isEqualTo(DocumentationException.ARTIFACT_INVALID));

        DocumentationWorld unbundled = DocumentationWorld.standard();
        unbundled.claims.remove(new ClaimKey(TOOL, "0.2.0"));
        assertThatThrownBy(() -> projector.plan(unbundled.inputs()))
                .satisfies(e -> assertThat(((DocumentationException) e).code()).isEqualTo(DocumentationException.CLAIM_MISSING));
    }

    /** Two pinned providers on one service root, or one host on two roots, refuse the projection. */
    @Test
    void serviceRootConflictsAreRefused() {
        DocumentationWorld world = DocumentationWorld.standard();
        world.replaceBinding("urn:c:flow", b -> withEndpoint(b, URI.create("http://host:7979/dcp/workflow.execute"), null));
        assertThatThrownBy(() -> projector.plan(world.inputs()))
                .satisfies(e -> assertThat(((DocumentationException) e).code()).isEqualTo(DocumentationException.SERVICE_ROOT_CONFLICT));

        DocumentationWorld twoRoots = DocumentationWorld.standard();
        Claim host = twoRoots.claims.get(new ClaimKey(HOST, "1.0.0"));
        Offer run = host.offers().get(0);
        Offer stream = new Offer("agent.stream", "Host agent.stream", run.consumerAccess(), run.offerInterface(), run.stability(),
                run.version(), false, null, run.documentation());
        twoRoots.put(DocumentationFixtures.withOffers(host, List.of(run, stream)));
        twoRoots.child("urn:c:agent2", "agent.stream", twoRoots.claims.get(new ClaimKey(HOST, "1.0.0")), "urn:client", "1.0.0", Map.of());
        twoRoots.httpBinding("urn:c:agent2", URI.create("http://host-b:7979/dcp/agent.stream"));
        assertThatThrownBy(() -> projector.plan(twoRoots.inputs())).hasMessageContaining("two service roots");
    }

    /** The re-check validator accepts the projector's own output and the claim hashes used are the pinned ones. */
    @Test
    void projectionsPassTheRecheck() {
        DocumentationWorld world = DocumentationWorld.standard().withIdentity();
        CapabilityDocumentation docs = projector.plan(world.inputs()).project(world.healthy(), T0);

        assertThat(new CapabilityDocumentationValidator().validate(docs, world.inputs()).valid()).isTrue();
        assertThat(component(docs, HOST).claimHash()).isEqualTo(ClaimHash.canonical(world.claims.get(new ClaimKey(HOST, "1.0.0"))));
    }

    // -------------------------------------------------------------------------------------------------------- helpers

    static Map<String, ReasonCode> reasons(CapabilityDocumentation docs) {
        Map<String, ReasonCode> reasons = new HashMap<>();
        docs.report().entries().forEach(entry -> reasons.put(entry.contractId().toString(), entry.reason()));
        return reasons;
    }

    static ReportEntry entry(CapabilityDocumentation docs, String contractId) {
        return docs.report().entries().stream().filter(e -> e.contractId().toString().equals(contractId)).findFirst().orElseThrow();
    }

    static DocumentedComponent component(CapabilityDocumentation docs, URI uri) {
        return docs.components().stream().filter(c -> c.componentUri().equals(uri)).findFirst().orElseThrow();
    }

    private static RuntimeBinding withPolicy(RuntimeBinding b, boolean enabled) {
        return new RuntimeBinding(b.bindingId(), b.contractId(), b.contractVersion(), b.targetEnvironment(), b.providerInstance(),
                b.consumerInstance(), new RuntimePolicy(enabled, 2000, "test", true), b.configuration(), b.deploymentControls(),
                b.lifecycle(), b.metadata());
    }

    private static RuntimeBinding withEndpoint(RuntimeBinding b, URI baseUrl, ConfigRef ref) {
        ProviderInstance p = b.providerInstance();
        return new RuntimeBinding(b.bindingId(), b.contractId(), b.contractVersion(), b.targetEnvironment(),
                new ProviderInstance(p.componentUri(), p.componentVersion(), p.instanceName(), DeploymentKind.CONTAINER, baseUrl, ref,
                        p.credentialsRef()),
                b.consumerInstance(), b.runtimePolicy(), b.configuration(), b.deploymentControls(), b.lifecycle(), b.metadata());
    }
}
