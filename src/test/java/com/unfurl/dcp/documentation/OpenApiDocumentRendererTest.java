package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimKey;
import com.unfurl.dcp.testing.DocumentationWorld;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.unfurl.dcp.testing.DocumentationWorld.FLOW;
import static com.unfurl.dcp.testing.DocumentationWorld.FLOW_ROOT;
import static com.unfurl.dcp.testing.DocumentationWorld.HOST;
import static com.unfurl.dcp.testing.DocumentationWorld.HOST_ROOT;
import static com.unfurl.dcp.testing.DocumentationWorld.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the OpenAPI renderer (DCP HLD-C2 G.2): envelopes selected by {@code const} operation, separate success and
 * failure branches, rebased references, per-operation security in both identity modes, hosted capabilities, product API
 * merging with renamed components, the reference guard and byte determinism. Rendered schemas are checked against
 * instances with a JSON Schema 2020-12 validator.
 */
class OpenApiDocumentRendererTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final OpenApiDocumentRenderer renderer = new OpenApiDocumentRenderer();

    /** A single-operation capability renders its envelope directly, with rebased $defs that resolve and validate. */
    @Test
    void singleOperationEnvelopeWithRebasedDefs() throws Exception {
        ObjectNode doc = render(DocumentationWorld.standard(), null).get(FLOW);

        JsonNode post = doc.at("/paths/~1dcp~1workflow.execute/post");
        assertThat(post.at("/requestBody/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/workflow.execute.workflow.execute.envelope");
        assertThat(doc.at("/components/schemas/workflow.execute.workflow.execute.request/properties/workflow.execute/$ref").asText())
                .isEqualTo("#/components/schemas/workflow.execute.workflow.execute.request/$defs/Value");
        assertThat(doc.at("/servers/0/url").asText()).isEqualTo(FLOW_ROOT.toString());

        String envelope = "workflow.execute.workflow.execute.envelope";
        assertThat(valid(doc, envelope, "{\"invocation\":{\"contractId\":\"urn:c:flow\",\"operation\":\"workflow.execute\","
                + "\"input\":{\"workflow.execute\":\"x\"}}}")).isTrue();
        assertThat(valid(doc, envelope, "{\"invocation\":{\"contractId\":\"urn:c:flow\",\"operation\":\"workflow.execute\","
                + "\"input\":{\"workflow.execute\":5}}}")).as("rebased $defs/Value is applied").isFalse();
        assertThat(valid(doc, envelope, "{\"invocation\":{\"contractId\":\"urn:c:flow\",\"operation\":\"other\",\"input\":{}}}")).isFalse();
    }

    /** Several operations render a oneOf of const-operation envelopes and an anyOf of results; {} input stays unambiguous. */
    @Test
    void multipleOperationsSelectAtTheEnvelopeLevel() throws Exception {
        DocumentationWorld world = DocumentationWorld.standard();
        Claim flow = world.claim("urn:flow", "1.0.0", "Flow", "workflow.execute", List.of("index", "summary"));
        world.put(flow);
        ObjectNode doc = render(world, null).get(FLOW);

        JsonNode post = doc.at("/paths/~1dcp~1workflow.execute/post");
        JsonNode body = post.at("/requestBody/content/application~1json/schema");
        assertThat(body.path("oneOf")).hasSize(2);
        assertThat(post.at("/responses/200/content/application~1json/schema/anyOf")).hasSize(2);
        assertThat(post.path("x-dcp-operations").has("index")).isTrue();
        assertThat(post.path("x-dcp-operations").has("summary")).isTrue();
        ObjectNode bodySchema = wrapper(doc, body);
        assertThat(validWith(bodySchema, "{\"invocation\":{\"contractId\":\"urn:c:flow\",\"operation\":\"index\",\"input\":{}}}")).isTrue();
        assertThat(validWith(bodySchema, "{\"invocation\":{\"contractId\":\"urn:c:flow\",\"operation\":\"nope\",\"input\":{}}}")).isFalse();
    }

    /** A fault validates against every result; a success must satisfy the response schema; a fault needs errorCode. */
    @Test
    void faultsHaveTheirOwnBranch() throws Exception {
        ObjectNode doc = render(DocumentationWorld.standard(), null).get(FLOW);
        String result = "workflow.execute.workflow.execute.result";

        assertThat(valid(doc, result, "{\"success\":false,\"errorCode\":\"index-unavailable\",\"output\":{}}")).isTrue();
        assertThat(valid(doc, result, "{\"success\":true,\"output\":{\"result\":\"ok\"}}")).isTrue();
        assertThat(valid(doc, result, "{\"success\":true,\"output\":{}}")).as("success missing required output field").isFalse();
        assertThat(valid(doc, result, "{\"success\":false,\"output\":{}}")).as("fault without errorCode").isFalse();
        assertThat(doc.at("/paths/~1dcp~1workflow.execute/post/x-dcp-faults")).isNotEmpty();
    }

    /** With identity: bearerAuth on protected operations, explicit empty security on public ones, never document-wide. */
    @Test
    void securityIsPerOperationWithIdentity() {
        DocumentationWorld world = DocumentationWorld.standard().withIdentity();
        withProductApi(world);
        ObjectNode doc = render(world, List.of("ops")).get(HOST);

        assertThat(doc.has("security")).isFalse();
        assertThat(doc.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(doc.at("/paths/~1dcp~1agent.run/post/security/0").has("bearerAuth")).isTrue();
        assertThat(doc.at("/paths/~1dcp~1agent.run/post/responses").has("401")).isTrue();
        assertThat(doc.at("/paths/~1health/get/security")).isEmpty();
        assertThat(doc.at("/paths/~1health/get/security").isArray()).isTrue();
        assertThat(doc.at("/paths/~1items/post/security/0").has("bearerAuth")).isTrue();
        assertThat(doc.at("/paths/~1items/post/responses").has("403")).isTrue();
        assertThat(doc.at("/paths/~1items/post/x-dcp-permission").asText()).isEqualTo("items.write");
        assertThat(doc.at("/components/schemas/agent.run.agent.run.envelope/properties").has("context"))
                .as("a protected component's envelope rejects a body context").isFalse();
    }

    /** Without identity: no scheme, no security anywhere, and the trusted-network statement. */
    @Test
    void noSecurityWithoutIdentity() {
        DocumentationWorld world = DocumentationWorld.standard();
        withProductApi(world);
        ObjectNode doc = render(world, List.of("ops")).get(HOST);

        assertThat(doc.at("/components").has("securitySchemes")).isFalse();
        List<String> security = new ArrayList<>();
        doc.findParents("security").forEach(parent -> security.add(parent.toString()));
        assertThat(security).isEmpty();
        assertThat(doc.at("/info/description").asText()).contains("no identity contract", "trusted network");
        assertThat(doc.at("/components/schemas/agent.run.agent.run.envelope/properties").has("context")).isTrue();
    }

    /**
     * Every document describes {@code GET /dcp/registrations} (HLD-C2 G.2). Without identity it has no security; with an
     * identity contract that protects the component it needs a bearer token with {@code dcp.registrations.read}.
     */
    @Test
    void registrationReportRouteIsDocumentedInBothIdentityModes() {
        Map<URI, ObjectNode> open = render(DocumentationWorld.standard(), null);
        assertThat(open).containsKeys(HOST, FLOW);
        for (ObjectNode doc : open.values()) {
            JsonNode get = doc.at("/paths/~1dcp~1registrations/get");
            assertThat(get.path("operationId").asText()).isEqualTo("dcp.registrations");
            assertThat(get.at("/responses/200/content/application~1json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/dcp.registrations.report");
            assertThat(get.at("/responses/200/headers/Cache-Control/schema/const").asText()).isEqualTo("no-store");
            assertThat(get.has("security")).isFalse();
            assertThat(get.has("x-dcp-permission")).isFalse();
            assertThat(get.path("responses").has("401")).isFalse();
        }

        for (ObjectNode doc : render(DocumentationWorld.standard().withIdentity(), null).values()) {
            JsonNode get = doc.at("/paths/~1dcp~1registrations/get");
            assertThat(get.at("/security/0").has(OpenApiDocumentRenderer.BEARER)).isTrue();
            assertThat(get.path("x-dcp-permission").asText()).isEqualTo(OpenApiDocumentRenderer.REGISTRATIONS_PERMISSION)
                    .isEqualTo("dcp.registrations.read");
            assertThat(get.path("responses").has("401")).isTrue();
            assertThat(get.path("responses").has("403")).isTrue();
        }
    }

    /**
     * The rendered report schema describes exactly what {@link RegistrationReportCodec} writes: a report with an HTTP
     * and a hosted registration validates; an HTTP registration without its path, or an upper-case surface, does not.
     */
    @Test
    void codecReportsValidateAgainstTheRenderedReportSchema() throws Exception {
        DocumentationWorld world = DocumentationWorld.standard();
        ObjectNode doc = render(world, null).get(HOST);
        String report = new String(RegistrationReportCodec.encode(
                world.report(HOST, List.of("ops"), "urn:c:agent", "urn:c:tool")), java.nio.charset.StandardCharsets.UTF_8);
        String schema = OpenApiDocumentRenderer.REGISTRATION_REPORT;

        assertThat(report).contains("\"surface\":\"http\"", "\"surface\":\"hosted_in_process\"").doesNotContain(":null");
        assertThat(valid(doc, schema, report)).isTrue();
        ObjectNode withoutPath = (ObjectNode) JSON.readTree(report);
        withoutPath.path("registrations").forEach(registration -> {
            if ("http".equals(registration.path("surface").asText())) ((ObjectNode) registration).remove("path");
        });
        assertThat(valid(doc, schema, withoutPath.toString())).as("an HTTP registration needs its path").isFalse();
        assertThat(valid(doc, schema, report.replace("\"hosted_in_process\"", "\"HOSTED_IN_PROCESS\""))).isFalse();
    }

    /** An unvalidated capability named {@code registrations} cannot shadow the report route (G.3 reservation). */
    @Test
    void aCapabilityCannotShadowTheReportRoute() {
        DocumentationWorld world = DocumentationWorld.standard();
        DocumentationPlan plan = new DocumentationProjector().plan(world.inputs());
        CapabilityDocumentation docs = plan.project(world.healthy(), T0);
        List<DocumentedComponent> components = docs.components().stream().map(c -> new DocumentedComponent(c.componentUri(),
                c.componentVersion(), c.name(), c.claimHash(), c.serviceRoot(), c.protectedComponent(),
                c.capabilities().stream().map(k -> k.surface() != EntrySurface.HTTP ? k : new DocumentedCapability("registrations",
                        k.capabilityVersion(), k.description(), k.contractId(), k.bindingId(), k.surface(), k.visibility(),
                        "/dcp/registrations", k.hostedBy(), k.providerComponentUri(), k.providerComponentVersion(),
                        k.operations(), k.faults())).toList(), c.apiDocuments())).toList();
        CapabilityDocumentation shadowed = new CapabilityDocumentation(docs.documentId(), docs.targetEnvironment(),
                docs.source(), docs.identity(), docs.surfaces(), components, docs.report(), docs.lifecycle());

        assertThatThrownBy(() -> renderer.render(shadowed, plan.artifacts()))
                .isInstanceOfSatisfying(DocumentationException.class, failure ->
                        assertThat(failure.code()).isEqualTo(DocumentationException.API_DOCUMENT_PATH_CONFLICT));
    }

    /** Hosted capabilities appear under x-dcp-hosted-capabilities with their own schemas, and have no path. */
    @Test
    void hostedCapabilitiesAreDocumentedFromTheirOwnClaim() {
        ObjectNode doc = render(DocumentationWorld.standard(), null).get(HOST);

        JsonNode hosted = doc.path("x-dcp-hosted-capabilities").get(0);
        assertThat(hosted.path("capability").asText()).isEqualTo("tool.lookup");
        assertThat(hosted.path("provider_component_uri").asText()).isEqualTo("urn:tool");
        assertThat(hosted.path("description").asText()).isEqualTo("Tool tool.lookup");
        assertThat(hosted.at("/operations/0/request/$ref").asText())
                .isEqualTo("#/components/schemas/hosted.urn-tool.tool.lookup.tool.lookup.request");
        assertThat(doc.path("paths").has("/dcp/tool.lookup")).isFalse();
    }

    /** Product components are renamed under the api id with every reference rewritten; unserved operations are absent. */
    @Test
    void productDocumentsAreMergedWithRenamedComponents() {
        DocumentationWorld world = DocumentationWorld.standard();
        withProductApi(world);
        ObjectNode doc = render(world, List.of("ops")).get(HOST);

        assertThat(doc.at("/components/schemas").has("ops-api.Item")).isTrue();
        assertThat(doc.at("/components/parameters").has("ops-api.Limit")).isTrue();
        assertThat(doc.at("/paths/~1items/post/requestBody/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/ops-api.Item");
        assertThat(doc.at("/paths/~1admin/delete").isMissingNode()).as("the admin surface is not served").isTrue();
    }

    /** A reference that cannot resolve never reaches a reader. */
    @Test
    void unresolvedReferencesAreRefused() {
        DocumentationWorld world = DocumentationWorld.standard();
        Claim host = world.claims.get(new ClaimKey(HOST, "1.0.0"));
        world.withApi(host, "broken", """
                openapi: 3.1.0
                info: {title: t, version: "1"}
                paths:
                  /x:
                    get:
                      x-dcp-surface: ops
                      x-dcp-access: public
                      responses: {"200": {description: ok, content: {application/json: {schema: {$ref: "#/components/schemas/Missing"}}}}}
                """, List.of("ops"));
        CapabilityDocumentation docs = withApis(world, projectedBypassingAdmission(world), List.of("ops"));

        assertThatThrownBy(() -> renderer.render(docs, world.store()))
                .isInstanceOf(DocumentationException.class).hasMessageContaining(DocumentationException.RENDER_REFERENCE_UNRESOLVED);
    }

    /** Equal inputs render byte-equal documents; reserved output kinds have no renderer. */
    @Test
    void renderingIsDeterministicAndReservedKindsAreRefused() {
        DocumentationWorld world = DocumentationWorld.standard();
        withProductApi(world);
        byte[] first = DocumentationCodec.treeToJson(render(world, List.of("ops")).get(HOST));
        byte[] second = DocumentationCodec.treeToJson(render(world, List.of("ops")).get(HOST));
        assertThat(first).isEqualTo(second);

        assertThatThrownBy(() -> DocumentationRenderer.requireRenderable(SurfaceKind.ASYNCAPI))
                .hasMessageContaining(DocumentationException.UNSUPPORTED_SURFACE);
    }

    // -------------------------------------------------------------------------------------------------------- helpers

    /** Adds the host's product API: a public health route, a protected items route and an unserved admin route. */
    static void withProductApi(DocumentationWorld world) {
        Claim host = world.claims.get(new ClaimKey(HOST, "1.0.0"));
        world.withApi(host, "ops-api", """
                openapi: 3.1.0
                info: {title: ops, version: "1"}
                paths:
                  /health:
                    get:
                      x-dcp-surface: ops
                      x-dcp-access: public
                      responses: {"200": {description: ok}}
                  /items:
                    post:
                      x-dcp-surface: ops
                      x-dcp-access: protected
                      x-dcp-permission: items.write
                      parameters: [{$ref: "#/components/parameters/Limit"}]
                      requestBody: {content: {application/json: {schema: {$ref: "#/components/schemas/Item"}}}}
                      responses: {"200": {description: ok}}
                  /admin:
                    delete:
                      x-dcp-surface: admin
                      x-dcp-access: protected
                      responses: {"204": {description: gone}}
                components:
                  schemas:
                    Item: {type: object, properties: {name: {type: string}}}
                  parameters:
                    Limit: {name: limit, in: query, schema: {type: integer}}
                """, List.of("ops", "admin"));
    }

    /** Plans and projects a healthy world, the host serving {@code served}, and renders it. */
    private Map<URI, ObjectNode> render(DocumentationWorld world, List<String> served) {
        DocumentationPlan plan = new DocumentationProjector().plan(world.inputs());
        Map<URI, RegistrationObservation> observations = new HashMap<>(world.healthy());
        if (served != null) {
            observations.put(HOST_ROOT, new RegistrationObservation.Reported(world.report(HOST, served, "urn:c:agent", "urn:c:tool"), T0));
        }
        return renderer.render(plan.project(observations, T0), plan.artifacts());
    }

    /** A projection of the world without the broken host API (which admission would refuse). */
    private static CapabilityDocumentation projectedBypassingAdmission(DocumentationWorld world) {
        DocumentationWorld clean = DocumentationWorld.standard();
        return new DocumentationProjector().plan(clean.inputs()).project(clean.healthy(), T0);
    }

    /** Swaps in the world's host API documents, as an unvalidated projection would carry them. */
    private static CapabilityDocumentation withApis(DocumentationWorld world, CapabilityDocumentation docs, List<String> served) {
        Claim host = world.claims.get(new ClaimKey(HOST, "1.0.0"));
        List<DocumentedApi> apis = host.apiDocuments().stream()
                .map(a -> new DocumentedApi(a.apiId(), a.openapi(), Visibility.INTERNAL, served)).toList();
        List<DocumentedComponent> components = docs.components().stream().map(c -> c.componentUri().equals(HOST)
                ? new DocumentedComponent(c.componentUri(), c.componentVersion(), c.name(), c.claimHash(), c.serviceRoot(),
                c.protectedComponent(), c.capabilities(), apis) : c).toList();
        return new CapabilityDocumentation(docs.documentId(), docs.targetEnvironment(), docs.source(), docs.identity(),
                docs.surfaces(), components, docs.report(), docs.lifecycle());
    }

    /** Validates an instance against a named component schema of a rendered document. */
    private static boolean valid(ObjectNode doc, String schemaName, String instance) throws Exception {
        ObjectNode ref = JSON.createObjectNode().put("$ref", "#/components/schemas/" + JsonReferences.escape(schemaName));
        return validWith(wrapper(doc, ref), instance);
    }

    /** A schema whose root is {@code schema} and whose components are the document's, so local refs resolve. */
    private static ObjectNode wrapper(ObjectNode doc, JsonNode schema) {
        ObjectNode wrapper = schema.deepCopy();
        wrapper.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        wrapper.set("components", doc.path("components"));
        return wrapper;
    }

    /** Runs the 2020-12 validator. */
    private static boolean validWith(ObjectNode schema, String instance) throws Exception {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema)
                .validate(JSON.readTree(instance)).isEmpty();
    }
}
