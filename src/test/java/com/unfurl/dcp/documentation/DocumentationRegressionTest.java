package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.unfurl.dcp.claim.*;
import com.unfurl.dcp.testing.DocumentationFixtures;
import com.unfurl.dcp.testing.DocumentationWorld;
import com.unfurl.dcp.validation.ErrorCode;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static com.unfurl.dcp.testing.DocumentationWorld.*;
import static org.assertj.core.api.Assertions.*;

/** Regression tests: schema dialect validation and lossless merging of a host's independently shipped API documents. */
class DocumentationRegressionTest {
    /** Rejects invalid keyword types at the root and inside nested schemas, rather than accepting parseable JSON. */
    @Test
    void rejectsInvalidSchemaKeywordShapes() {
        for (String schema : List.of(
                "{\"type\":\"object\",\"required\":\"bad\"}",
                "{\"type\":\"object\",\"properties\":{\"x\":{\"minLength\":-1}}}",
                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"unknown\"}}}",
                "{\"type\":\"object\",\"allOf\":{}}")) {
            DocumentationWorld world = DocumentationWorld.standard();
            Claim host = world.claims.get(new ClaimKey(HOST, "1.0.0"));
            ArtifactRef ref = world.file("dcp/invalid.json", schema, ArtifactMediaType.SCHEMA_JSON);
            Offer offer = DocumentationFixtures.documented(host.offers().get(0),
                    DocumentationFixtures.operation("run", ref, ref));
            var report = new ClaimArtifactValidator().validate(
                    DocumentationFixtures.withOffers(host, List.of(offer)), world.store());
            assertThat(report.valid()).as(schema).isFalse();
            assertThat(report.diagnostics()).anyMatch(d -> d.code() == ErrorCode.SCHEMA_DOCUMENT_INVALID);
        }
    }

    /** Accepts a recursive local schema with boolean subschemas and definitions under the bundled dialect. */
    @Test
    void acceptsValidRecursiveSchemaOffline() {
        DocumentationWorld world = DocumentationWorld.standard();
        Claim host = world.claims.get(new ClaimKey(HOST, "1.0.0"));
        ArtifactRef ref = world.file("dcp/valid.json", """
                {"type":"object","properties":{"next":{"$ref":"#"},"excluded":false},
                 "$defs":{"value":{"type":["string","null"]}},"additionalProperties":true}
                """, ArtifactMediaType.SCHEMA_JSON);
        var claim = DocumentationFixtures.withOffers(host, List.of(DocumentationFixtures.documented(
                host.offers().get(0), DocumentationFixtures.operation("run", ref, ref))));
        assertThat(new ClaimArtifactValidator().validate(claim, world.store()).valid()).isTrue();
    }

    /** Refuses remote references and unknown dialects through diagnostics, without permitting network resolution. */
    @Test
    void refusesExternalResolution() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String field : List.of("$ref", "$schema")) {
            var schema = mapper.createObjectNode().put("type", "object").put(field, "https://invalid.example/schema");
            DocumentationWorld world = DocumentationWorld.standard();
            Claim host = world.claims.get(new ClaimKey(HOST, "1.0.0"));
            ArtifactRef ref = world.file("dcp/external.json", schema.toString(), ArtifactMediaType.SCHEMA_JSON);
            var claim = DocumentationFixtures.withOffers(host, List.of(DocumentationFixtures.documented(
                    host.offers().get(0), DocumentationFixtures.operation("run", ref, ref))));
            assertThat(new ClaimArtifactValidator().validate(claim, world.store()).valid()).isFalse();
        }
    }

    /** Preserves disjoint HTTP methods sharing a URL and scopes inherited parameters to their original operation. */
    @Test
    void mergesDisjointMethodsAndPreservesParameterScope() {
        JsonNode item = render("\"summary\":\"Items\",\"parameters\":[{\"name\":\"limit\",\"in\":\"query\",\"schema\":{\"type\":\"integer\"}}],",
                "\"summary\":\"Items\",").path("paths").path("/items");
        assertThat(item.has("get")).isTrue();
        assertThat(item.has("post")).isTrue();
        assertThat(item.path("summary").asText()).isEqualTo("Items");
        assertThat(item.path("get").path("parameters").get(0).path("name").asText()).isEqualTo("limit");
        assertThat(item.path("post").has("parameters")).isFalse();
        assertThat(item.has("parameters")).isFalse();
    }

    /**
     * Refuses unequal shared metadata rather than choosing whichever document happened to be merged last: at admission
     * ({@link ClaimArtifactValidator}), so such a claim never reaches a renderer, and again in the renderer as a guard.
     */
    @Test
    void refusesConflictingPathMetadata() {
        var report = validate("\"summary\":\"First\",", "\"summary\":\"Second\",");
        assertThat(report.valid()).isFalse();
        assertThat(report.diagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo(ErrorCode.API_DOCUMENT_PATH_CONFLICT);
            assertThat(d.message()).contains("/items", "summary");
        });
        assertThatThrownBy(() -> render("\"summary\":\"First\",", "\"summary\":\"Second\",", "", "", false))
                .isInstanceOf(DocumentationException.class)
                .hasMessageContaining("API_DOCUMENT_PATH_CONFLICT");
    }

    /** Shared path metadata that is equal after component renaming is accepted; path parameters never conflict. */
    @Test
    void acceptsEqualSharedMetadataAndDifferentPathParameters() {
        var report = validate("\"summary\":\"Items\",\"parameters\":[{\"name\":\"a\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}}],",
                "\"summary\":\"Items\",\"parameters\":[{\"name\":\"b\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}}],");
        assertThat(report.valid()).isTrue();
    }

    /** Retains an operation's override of an inherited referenced parameter after components are renamed. */
    @Test
    void preservesReferencedParameterOverrides() {
        String inherited = "\"parameters\":[{\"$ref\":\"#/components/parameters/Limit\"}],";
        String override = "\"parameters\":[{\"name\":\"limit\",\"in\":\"query\",\"description\":\"override\",\"schema\":{\"type\":\"integer\"}}],";
        String components = ",\"components\":{\"parameters\":{\"Limit\":{\"name\":\"limit\",\"in\":\"query\",\"schema\":{\"type\":\"integer\"}}}}";
        var parameters = render(inherited, "", override, components).path("paths").path("/items").path("get").path("parameters");
        assertThat(parameters.size()).isEqualTo(1);
        assertThat(parameters.get(0).path("description").asText()).isEqualTo("override");
    }

    /** Test Data Builder: renders two separately validated documents on the same host and URL. */
    private static JsonNode render(String getMetadata, String postMetadata) {
        return render(getMetadata, postMetadata, "", "", true);
    }

    /** Test Data Builder: as {@link #render}, validating the two documents only. */
    private static com.unfurl.dcp.validation.SchemaValidationReport validate(String getMetadata, String postMetadata) {
        DocumentationWorld world = DocumentationWorld.standard();
        Claim host = world.claims.get(new ClaimKey(HOST, "1.0.0"));
        return new ClaimArtifactValidator().validate(
                DocumentationFixtures.withApiDocuments(host, documents(world, getMetadata, postMetadata, "", "", null)), world.store());
    }

    /** The two single-method documents of a scenario; {@code apis}, when given, collects their documented form. */
    private static List<ApiDocument> documents(DocumentationWorld world, String getMetadata, String postMetadata,
                                               String getOperationExtras, String getComponents, List<DocumentedApi> apis) {
        List<ApiDocument> apiDocuments = new ArrayList<>();
        for (String method : List.of("get", "post")) {
            String metadata = method.equals("get") ? getMetadata : postMetadata;
            String json = "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Items\",\"version\":\"1\"},\"paths\":{\"/items\":{" + metadata
                    + "\"" + method + "\":{" + (method.equals("get") ? getOperationExtras : "")
                    + "\"x-dcp-surface\":\"items\",\"x-dcp-access\":\"public\",\"responses\":{\"200\":{\"description\":\"OK\"}}}}}"
                    + (method.equals("get") ? getComponents : "") + "}";
            ArtifactRef ref = world.file("dcp/" + method + ".json", json, ArtifactMediaType.OPENAPI_JSON);
            apiDocuments.add(new ApiDocument(method, "Items", ref, List.of("items")));
            if (apis != null) apis.add(new DocumentedApi(method, ref, Visibility.INTERNAL, List.of("items")));
        }
        return apiDocuments;
    }

    /** Test Data Builder: includes operation overrides and local parameter components where the scenario requires them. */
    private static JsonNode render(String getMetadata, String postMetadata, String getOperationExtras, String getComponents) {
        return render(getMetadata, postMetadata, getOperationExtras, getComponents, true);
    }

    /** Test Data Builder: {@code admitted} false renders documents that admission would refuse, to exercise the guard. */
    private static JsonNode render(String getMetadata, String postMetadata, String getOperationExtras, String getComponents,
                                   boolean admitted) {
        DocumentationWorld world = DocumentationWorld.standard();
        Claim host = world.claims.get(new ClaimKey(HOST, "1.0.0"));
        List<DocumentedApi> apis = new ArrayList<>();
        List<ApiDocument> apiDocuments = documents(world, getMetadata, postMetadata, getOperationExtras, getComponents, apis);
        if (admitted) {
            assertThat(new ClaimArtifactValidator().validate(
                    DocumentationFixtures.withApiDocuments(host, apiDocuments), world.store()).valid()).isTrue();
        }
        var docs = new DocumentationProjector().plan(world.inputs()).project(world.healthy(), T0);
        var components = docs.components().stream().map(c -> c.componentUri().equals(HOST)
                ? new DocumentedComponent(c.componentUri(), c.componentVersion(), c.name(), c.claimHash(),
                    c.serviceRoot(), c.protectedComponent(), c.capabilities(), apis) : c).toList();
        var modified = new CapabilityDocumentation(docs.documentId(), docs.targetEnvironment(), docs.source(),
                docs.identity(), docs.surfaces(), components, docs.report(), docs.lifecycle());
        return new OpenApiDocumentRenderer().render(modified, world.store()).get(HOST);
    }
}
