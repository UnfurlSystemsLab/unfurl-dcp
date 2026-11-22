package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.ArtifactMediaType;
import com.unfurl.dcp.claim.ArtifactRef;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimKey;
import com.unfurl.dcp.documentation.ServiceRootResolver.ResolvedEndpoint;
import com.unfurl.dcp.runtimebinding.ConfigRef;
import com.unfurl.dcp.runtimebinding.ProviderInstance;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.testing.DocumentationFixtures;
import com.unfurl.dcp.testing.DocumentationWorld;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.substrate.ports.host.ResolvedConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.unfurl.dcp.testing.DocumentationWorld.FLOW;
import static com.unfurl.dcp.testing.DocumentationWorld.HOST;
import static com.unfurl.dcp.testing.DocumentationWorld.HOST_ROOT;
import static com.unfurl.dcp.testing.DocumentationWorld.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the projection's supporting pieces: freshness of rendered views, the codecs, service-root resolution through
 * config references, filters, artifact stores and the artifact validator's rules.
 */
class DocumentationSupportTest {

    /** Served surfaces change the OpenAPI bytes while statuses stay; times alone change the report but not OpenAPI. */
    @Test
    void renderedViewsAreFresh() {
        DocumentationWorld world = DocumentationWorld.standard();
        OpenApiDocumentRendererTest.withProductApi(world);
        DocumentationPlan plan = new DocumentationProjector().plan(world.inputs());
        Map<URI, RegistrationObservation> serving = new HashMap<>(world.healthy());
        serving.put(HOST_ROOT, new RegistrationObservation.Reported(world.report(HOST, List.of("ops"), "urn:c:agent", "urn:c:tool"), T0));
        Map<URI, RegistrationObservation> notServing = new HashMap<>(world.healthy());
        Map<URI, RegistrationObservation> later = new HashMap<>();
        serving.forEach((root, obs) -> later.put(root, new RegistrationObservation.Reported(
                ((RegistrationObservation.Reported) obs).report(), T0.plusSeconds(10))));

        CapabilityDocumentation a = plan.project(serving, T0);
        CapabilityDocumentation b = plan.project(notServing, T0);
        CapabilityDocumentation c = plan.project(later, T0.plusSeconds(10));
        OpenApiDocumentRenderer renderer = new OpenApiDocumentRenderer();

        assertThat(openApi(renderer, a, plan)).isNotEqualTo(openApi(renderer, b, plan));
        assertThat(DocumentationProjectorTest.reasons(a)).isEqualTo(DocumentationProjectorTest.reasons(b));
        assertThat(openApi(renderer, a, plan)).isEqualTo(openApi(renderer, c, plan));
        assertThat(new HumanSummaryRenderer().render(a, plan.artifacts())).isEqualTo(new HumanSummaryRenderer().render(c, plan.artifacts()));
        assertThat(DocumentationCodec.toJson(a)).isNotEqualTo(DocumentationCodec.toJson(c));

        Map<URI, RegistrationObservation> unreachable = Map.of(HOST_ROOT, new RegistrationObservation.Unreachable("timeout", T0));
        Map<URI, RegistrationObservation> refused = Map.of(HOST_ROOT, new RegistrationObservation.Unauthorized(401, T0));
        assertThat(DocumentationCodec.toJson(plan.project(unreachable, T0))).isNotEqualTo(DocumentationCodec.toJson(plan.project(refused, T0)));
    }

    /** The registration report and the projection round-trip through their codecs; malformed reports are refused. */
    @Test
    void codecsRoundTrip() {
        DocumentationWorld world = DocumentationWorld.standard();
        RegistrationReport report = world.report(HOST, List.of("ops"), "urn:c:agent", "urn:c:tool");
        assertThat(RegistrationReportCodec.decode(RegistrationReportCodec.encode(report))).isEqualTo(report);
        assertThat(new String(RegistrationReportCodec.encode(report), StandardCharsets.UTF_8))
                .contains("\"provider_claim_hash\"", "\"served_surfaces\"", "\"HOSTED_IN_PROCESS\"");
        assertThatThrownBy(() -> RegistrationReportCodec.decode("{\"component_uri\":\"urn:x\"}".getBytes()))
                .isInstanceOf(RegistrationReportCodec.RegistrationReportException.class);
        assertThatThrownBy(() -> RegistrationReportCodec.decode("not json".getBytes()))
                .isInstanceOf(RegistrationReportCodec.RegistrationReportException.class);

        CapabilityDocumentation docs = new DocumentationProjector().plan(world.inputs()).project(world.healthy(), T0);
        assertThat(DocumentationCodec.fromJson(DocumentationCodec.toJson(docs))).isEqualTo(docs);
    }

    /** A host may not report a hosted component under its own identity, or the reverse. */
    @Test
    void httpRegistrationsCarryTheHostIdentity() {
        assertThatThrownBy(() -> new RegistrationReport(HOST, "1.0.0", "a".repeat(64), T0, List.of(
                new Registration(URI.create("urn:c:x"), "1.0.0", "x", URI.create("urn:other"), "1.0.0", "a".repeat(64),
                        EntrySurface.HTTP, "/dcp/x")), List.of()))
                .hasMessageContaining("host's own identity");
    }

    /** A base_url_ref resolves through the config port under the reference's own tenant; its value is the endpoint. */
    @Test
    void baseUrlRefResolvesThroughTheConfigPort() {
        DocumentationWorld world = DocumentationWorld.standard();
        RuntimeBinding flow = world.bindings.stream().filter(b -> b.contractId().toString().equals("urn:c:flow")).findFirst().orElseThrow();
        ProviderInstance p = flow.providerInstance();
        RuntimeBinding byRef = new RuntimeBinding(flow.bindingId(), flow.contractId(), flow.contractVersion(), flow.targetEnvironment(),
                new ProviderInstance(p.componentUri(), p.componentVersion(), p.instanceName(), p.deploymentKind(), null,
                        new ConfigRef("config://acme/flow/endpoint"), null),
                null, flow.runtimePolicy(), flow.configuration(), flow.deploymentControls(), flow.lifecycle(), null);
        var contract = world.children.stream().filter(c -> c.contractId().toString().equals("urn:c:flow")).findFirst().orElseThrow();
        String[] tenant = new String[1];
        ResolvedEndpoint resolved = new ServiceRootResolver().resolve(byRef, contract, (ref, context) -> {
            tenant[0] = context.tenantId();
            return Optional.of(new ResolvedConfig(ref, "r1", Map.of("value", "https://flow.acme.test/base/dcp/workflow.execute")));
        });

        assertThat(tenant[0]).isEqualTo("acme");
        assertThat(resolved).isEqualTo(new ResolvedEndpoint.ServiceRoot(URI.create("https://flow.acme.test/base/dcp/workflow.execute"),
                URI.create("https://flow.acme.test/base")));
        assertThat(new ServiceRootResolver().resolve(byRef, contract, (ref, context) -> Optional.empty()))
                .isInstanceOf(ResolvedEndpoint.Missing.class);
        assertThat(ServiceRootResolver.shape(URI.create("http://h:1/dcp/workflow.execute?x=1"), "workflow.execute"))
                .isInstanceOf(ResolvedEndpoint.Mismatch.class);
    }

    /** Filters narrow views without changing reasons; another tenant sees nothing. */
    @Test
    void filtersNarrowViews() {
        DocumentationWorld world = DocumentationWorld.standard();
        CapabilityDocumentation docs = new DocumentationProjector().plan(world.inputs()).project(world.healthy(), T0);

        CapabilityDocumentation flowOnly = new DocumentationFilter(null, null, URI.create("urn:c:flow"), null, null).apply(docs);
        assertThat(flowOnly.report().entries()).extracting(ReportEntry::contractId).containsExactly(URI.create("urn:c:flow"));
        assertThat(flowOnly.components()).extracting(DocumentedComponent::componentUri).containsExactly(FLOW);
        assertThat(new DocumentationFilter("other", null, null, null, null).apply(docs).report().entries()).isEmpty();
        assertThat(new DocumentationFilter("acme", "test", null, null, EntrySurface.HOSTED_IN_PROCESS).apply(docs).report().entries())
                .extracting(ReportEntry::contractId).containsExactly(URI.create("urn:c:tool"));
        assertThat(DocumentationFilter.NONE.apply(docs)).isEqualTo(docs);
    }

    /** Stores verify hashes; the archive store reads a jar as data, once. */
    @Test
    void storesVerifyHashes(@TempDir Path dir) throws Exception {
        byte[] bytes = "{\"type\":\"object\"}".getBytes(StandardCharsets.UTF_8);
        ArtifactRef ref = new ArtifactRef("dcp/s.json", ArtifactMediaType.SCHEMA_JSON, DocumentationFixtures.sha256(bytes));
        Path store = Files.createDirectories(dir.resolve("artifacts/sha256"));
        Files.write(store.resolve(ref.sha256()), bytes);
        assertThat(new ContentAddressedArtifactStore(store).read(ref)).isEqualTo(bytes);
        Files.writeString(store.resolve(ref.sha256()), "tampered");
        assertThatThrownBy(() -> new ContentAddressedArtifactStore(store).read(ref))
                .satisfies(e -> assertThat(((DocumentationArtifactStore.ArtifactUnavailableException) e).code()).isEqualTo(ErrorCode.ARTIFACT_HASH_MISMATCH));

        Path jar = dir.resolve("c.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("dcp/s.json"));
            zip.write(bytes);
            zip.closeEntry();
        }
        PackagedArtifactStore archive = PackagedArtifactStore.fromArchive(jar);
        Files.delete(jar);
        assertThat(archive.read(ref)).isEqualTo(bytes);
        assertThatThrownBy(() -> archive.read(new ArtifactRef("dcp/missing.json", ArtifactMediaType.SCHEMA_JSON, ref.sha256())))
                .satisfies(e -> assertThat(((DocumentationArtifactStore.ArtifactUnavailableException) e).code()).isEqualTo(ErrorCode.ARTIFACT_UNAVAILABLE));
    }

    /** The artifact validator's payload-schema and API-document rules. */
    @Test
    void artifactValidatorRules() {
        assertThat(schemaCodes("{\"type\":\"string\"}")).contains(ErrorCode.SCHEMA_ROOT_NOT_OBJECT);
        assertThat(schemaCodes("{\"type\":\"object\",\"$id\":\"urn:x\"}")).contains(ErrorCode.SCHEMA_DOCUMENT_INVALID);
        assertThat(schemaCodes("{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/missing\"}}}")).contains(ErrorCode.SCHEMA_DOCUMENT_INVALID);
        assertThat(schemaCodes("{\"type\":\"object\",\"properties\":{\"$ref\":{\"type\":\"string\"}}}")).as("a property named $ref is a name").isEmpty();

        String noAccess = "openapi: 3.1.0\ninfo: {title: t, version: \"1\"}\npaths:\n  /a:\n    get: {x-dcp-surface: ops, responses: {\"200\": {description: ok}}}\n";
        String dcpPath = "openapi: 3.1.0\ninfo: {title: t, version: \"1\"}\npaths:\n  /dcp/x:\n    get: {x-dcp-surface: ops, x-dcp-access: public, responses: {\"200\": {description: ok}}}\n";
        String servers = "openapi: 3.1.0\ninfo: {title: t, version: \"1\"}\nservers: [{url: http://x}]\npaths: {}\n";
        String old = "openapi: 3.0.3\ninfo: {title: t, version: \"1\"}\npaths: {}\n";
        for (String doc : List.of(noAccess, dcpPath, servers, old)) {
            assertThat(apiCodes(doc)).as(doc).contains(ErrorCode.API_DOCUMENT_INVALID);
        }
    }

    // -------------------------------------------------------------------------------------------------------- helpers

    private static byte[] openApi(OpenApiDocumentRenderer renderer, CapabilityDocumentation docs, DocumentationPlan plan) {
        return DocumentationCodec.treeToJson(renderer.render(docs, plan.artifacts()).get(HOST));
    }

    private static List<ErrorCode> schemaCodes(String schema) {
        DocumentationWorld world = DocumentationWorld.standard();
        Claim host = world.claims.get(new ClaimKey(HOST, "1.0.0"));
        ArtifactRef ref = world.file("dcp/s.json", schema, ArtifactMediaType.SCHEMA_JSON);
        Claim claim = DocumentationFixtures.withOffers(host, List.of(DocumentationFixtures.documented(host.offers().get(0),
                DocumentationFixtures.operation("run", ref, ref))));
        return new ClaimArtifactValidator().validate(claim, world.store()).diagnostics().stream().map(d -> d.code()).toList();
    }

    private static List<ErrorCode> apiCodes(String yaml) {
        DocumentationWorld world = DocumentationWorld.standard();
        Claim host = world.withApi(world.claims.get(new ClaimKey(HOST, "1.0.0")), "api", yaml, List.of("ops"));
        return new ClaimArtifactValidator().validate(host, world.store()).diagnostics().stream().map(d -> d.code()).toList();
    }

}
