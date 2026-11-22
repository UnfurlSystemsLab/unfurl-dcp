package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.unfurl.dcp.claim.ApiDocument;
import com.unfurl.dcp.claim.ArtifactMediaType;
import com.unfurl.dcp.claim.ArtifactRef;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.OperationDocumentation;
import com.unfurl.dcp.documentation.DocumentationArtifactStore.ArtifactUnavailableException;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.SchemaValidationReport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Validator: checks the contents of every file a claim ships for documentation (DCP HLD-C2 A.6, A.12, A.13). It needs
 * the bytes, so it complements {@code ClaimValidator}, which checks only the references.
 *
 * <p>For each payload schema: readable, hash verified, JSON, validated against the bundled draft 2020-12 meta-schema, no
 * scope-changing keywords, every {@code $ref} a JSON-Pointer fragment that resolves inside the file, and a root
 * {@code type: object}. For each API document: OpenAPI 3.1, the A.13 rules on every operation, references only into its
 * own components, and no method and path repeated across the claim's documents. Every problem is a diagnostic naming the
 * file and the JSON Pointer of the offending node; nothing is thrown for expected failures.
 *
 * <p>Fabric runs it at catalog scan, and projectors run it when they plan.
 */
public final class ClaimArtifactValidator {
    /** The only accepted {@code $schema} value. */
    public static final String JSON_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema";
    /** HTTP methods an OpenAPI path item may define. */
    static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    /** OpenAPI components sections that may be referenced. */
    static final Set<String> COMPONENT_SECTIONS = Set.of("schemas", "responses", "parameters", "requestBodies", "headers",
            "examples", "links", "callbacks", "pathItems");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final SchemaSyntaxValidator syntax = new HarrelSchemaSyntaxValidator();

    /**
     * Validates every shipped artifact of a claim.
     *
     * @param claim the claim; its references have passed {@code ClaimValidator}
     * @param store where the claim's files are read from
     */
    public SchemaValidationReport validate(Claim claim, DocumentationArtifactStore store) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(store, "store");
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Offer offer : claim.offers()) {
            if (offer.documentation() == null) continue;
            for (OperationDocumentation operation : offer.documentation().operations()) {
                String at = "offers[" + offer.capability() + "].documentation.operations[" + operation.operation() + "]";
                schema(operation.requestSchema(), at + ".request_schema", store, diagnostics);
                schema(operation.responseSchema(), at + ".response_schema", store, diagnostics);
            }
        }
        Map<String, String> routes = new HashMap<>();
        Map<String, Map<String, SharedMember>> pathMembers = new HashMap<>();
        for (ApiDocument document : claim.apiDocuments()) {
            apiDocument(document, store, routes, pathMembers, diagnostics);
        }
        return new SchemaValidationReport(diagnostics);
    }

    /** Reads and parses one payload schema, then applies the schema rules. */
    private void schema(ArtifactRef ref, String at, DocumentationArtifactStore store, List<Diagnostic> diagnostics) {
        Optional<JsonNode> parsed = read(ref, at, store, diagnostics);
        if (parsed.isEmpty()) return;
        JsonNode root = parsed.get();
        String where = at + " (" + ref.path() + ")";
        if (!root.isObject()) {
            diagnostics.add(Diagnostic.error(ErrorCode.SCHEMA_ROOT_NOT_OBJECT, "the schema is not a JSON object", where + "#"));
            return;
        }
        syntax.validate(root).forEach(violation -> diagnostics.add(Diagnostic.error(
                ErrorCode.SCHEMA_DOCUMENT_INVALID, violation.message(), where + "#" + violation.pointer())));
        JsonNode declared = root.get("$schema");
        if (declared != null && !(declared.isTextual() && JSON_SCHEMA_2020_12.equals(declared.asText()))) {
            diagnostics.add(Diagnostic.error(ErrorCode.SCHEMA_DOCUMENT_INVALID,
                    "$schema must be " + JSON_SCHEMA_2020_12, where + "#/$schema"));
        }
        JsonReferences.forEachForbiddenKeyword(root, (pointer, keyword) -> diagnostics.add(Diagnostic.error(
                ErrorCode.SCHEMA_DOCUMENT_INVALID, keyword + " is not allowed; use JSON-Pointer $refs only", where + "#" + pointer)));
        JsonReferences.forEachRef(root, (pointer, value) -> {
            if (!value.startsWith("#") || !JsonReferences.resolves(root, value)) {
                diagnostics.add(Diagnostic.error(ErrorCode.SCHEMA_DOCUMENT_INVALID,
                        "$ref " + value + " is not a JSON-Pointer fragment that resolves inside the file", where + "#" + pointer));
            }
        });
        JsonNode type = root.get("type");
        if (type == null || !type.isTextual() || !"object".equals(type.asText())) {
            diagnostics.add(Diagnostic.error(ErrorCode.SCHEMA_ROOT_NOT_OBJECT,
                    "an operation payload schema has a root type: object (input and output are maps)", where + "#/type"));
        }
    }

    /** Reads and parses one API document, then applies the A.13 rules. */
    private void apiDocument(ApiDocument document, DocumentationArtifactStore store, Map<String, String> routes,
                             Map<String, Map<String, SharedMember>> pathMembers, List<Diagnostic> diagnostics) {
        String at = "api_documents[" + document.apiId() + "]";
        Optional<JsonNode> parsed = read(document.openapi(), at, store, diagnostics);
        if (parsed.isEmpty()) return;
        JsonNode root = parsed.get();
        String where = at + " (" + document.openapi().path() + ")";
        if (!root.isObject() || !root.path("openapi").isTextual() || !root.path("openapi").asText().startsWith("3.1.")) {
            diagnostics.add(Diagnostic.error(ErrorCode.API_DOCUMENT_INVALID, "the document must be OpenAPI 3.1", where + "#/openapi"));
            return;
        }
        for (String forbidden : List.of("servers", "security")) {
            if (root.has(forbidden)) {
                diagnostics.add(Diagnostic.error(ErrorCode.API_DOCUMENT_INVALID,
                        forbidden + " is derived by the projection and must not be declared", where + "#/" + forbidden));
            }
        }
        if (root.path("components").has("securitySchemes")) {
            diagnostics.add(Diagnostic.error(ErrorCode.API_DOCUMENT_INVALID,
                    "components.securitySchemes is derived by the projection and must not be declared", where + "#/components/securitySchemes"));
        }
        JsonReferences.forEachForbiddenKeyword(root, (pointer, keyword) -> diagnostics.add(Diagnostic.error(
                ErrorCode.API_DOCUMENT_INVALID, keyword + " is not allowed", where + "#" + pointer)));
        JsonReferences.forEachRef(root, (pointer, value) -> {
            if (!isComponentRef(value) || !JsonReferences.resolves(root, value)) {
                diagnostics.add(Diagnostic.error(ErrorCode.API_DOCUMENT_INVALID,
                        "$ref " + value + " must target this document's own #/components/<section>/<name>", where + "#" + pointer));
            }
        });
        Set<String> surfaces = Set.copyOf(document.surfaces());
        root.path("paths").fields().forEachRemaining(path -> {
            String pathPointer = where + "#/paths/" + JsonReferences.escape(path.getKey());
            if (path.getKey().equals("/dcp") || path.getKey().startsWith("/dcp/")) {
                diagnostics.add(Diagnostic.error(ErrorCode.API_DOCUMENT_INVALID,
                        "DCP paths are projected from offers and must not appear", pathPointer));
            }
            JsonNode item = path.getValue();
            for (String forbidden : List.of("servers")) {
                if (item.has(forbidden)) {
                    diagnostics.add(Diagnostic.error(ErrorCode.API_DOCUMENT_INVALID, "servers must not be declared", pathPointer + "/servers"));
                }
            }
            item.fields().forEachRemaining(member -> {
                if (!METHODS.contains(member.getKey()) && !member.getKey().equals("parameters")) {
                    sharedMember(document.apiId(), path.getKey(), member.getKey(), member.getValue(), pathMembers,
                            pathPointer + "/" + JsonReferences.escape(member.getKey()), diagnostics);
                }
                if (!METHODS.contains(member.getKey())) return;
                operation(member.getValue(), surfaces, pathPointer + "/" + member.getKey(), diagnostics);
                String route = member.getKey().toUpperCase() + " " + path.getKey();
                String previous = routes.putIfAbsent(route, document.apiId());
                if (previous != null && !previous.equals(document.apiId())) {
                    diagnostics.add(Diagnostic.error(ErrorCode.API_DOCUMENT_PATH_CONFLICT,
                            route + " is declared by both " + previous + " and " + document.apiId(), pathPointer + "/" + member.getKey()));
                }
            });
        });
    }

    /** A path-level member (summary, description, extensions) as one document declares it, after renaming. */
    private record SharedMember(String apiId, JsonNode value) { }

    /**
     * Rule (A.13): when several of a claim's API documents declare the same path, the rendered document merges their path
     * items into one, so every path-level member they share must be identical after component renaming. Path-level
     * {@code parameters} are exempt: the renderer moves them into each document's own operations. Checking it here
     * refuses the claim at admission instead of failing every later render.
     */
    private static void sharedMember(String apiId, String path, String member, JsonNode value,
                                     Map<String, Map<String, SharedMember>> pathMembers, String at, List<Diagnostic> diagnostics) {
        JsonNode rendered = JsonReferences.prefixComponentRefs(value, apiId);
        SharedMember previous = pathMembers.computeIfAbsent(path, key -> new HashMap<>())
                .putIfAbsent(member, new SharedMember(apiId, rendered));
        if (previous != null && !previous.apiId().equals(apiId) && !previous.value().equals(rendered)) {
            diagnostics.add(Diagnostic.error(ErrorCode.API_DOCUMENT_PATH_CONFLICT, "path " + path + " member " + member
                    + " differs between " + previous.apiId() + " and " + apiId + "; shared path metadata must be identical", at));
        }
    }

    /** The A.13 rules for one operation. */
    private static void operation(JsonNode operation, Set<String> surfaces, String at, List<Diagnostic> diagnostics) {
        JsonNode surface = operation.get("x-dcp-surface");
        if (surface == null || !surface.isTextual() || !surfaces.contains(surface.asText())) {
            diagnostics.add(Diagnostic.error(ErrorCode.API_DOCUMENT_INVALID,
                    "x-dcp-surface must name one of the document's surfaces " + surfaces, at + "/x-dcp-surface"));
        }
        JsonNode access = operation.get("x-dcp-access");
        if (access == null || !access.isTextual() || !(access.asText().equals("public") || access.asText().equals("protected"))) {
            diagnostics.add(Diagnostic.error(ErrorCode.API_DOCUMENT_INVALID,
                    "x-dcp-access is required and must be public or protected", at + "/x-dcp-access"));
        }
        JsonNode permission = operation.get("x-dcp-permission");
        if (permission != null && (!permission.isTextual() || permission.asText().isBlank())) {
            diagnostics.add(Diagnostic.error(ErrorCode.API_DOCUMENT_INVALID,
                    "x-dcp-permission must be a non-blank string", at + "/x-dcp-permission"));
        }
        for (String forbidden : List.of("security", "servers")) {
            if (operation.has(forbidden)) {
                diagnostics.add(Diagnostic.error(ErrorCode.API_DOCUMENT_INVALID,
                        forbidden + " is derived by the projection and must not be declared", at + "/" + forbidden));
            }
        }
    }

    /** Whether a reference targets {@code #/components/<section>/<name>...} with a known section. */
    static boolean isComponentRef(String ref) {
        if (ref == null || !ref.startsWith("#/components/")) return false;
        String[] parts = ref.substring("#/components/".length()).split("/", 3);
        return parts.length >= 2 && COMPONENT_SECTIONS.contains(parts[0]) && !parts[1].isEmpty();
    }

    /** Reads, verifies and parses one file by its media type. */
    private static Optional<JsonNode> read(ArtifactRef ref, String at, DocumentationArtifactStore store, List<Diagnostic> diagnostics) {
        byte[] bytes;
        try {
            bytes = store.read(ref);
        } catch (ArtifactUnavailableException unavailable) {
            diagnostics.add(Diagnostic.error(unavailable.code(), unavailable.getMessage(), at));
            return Optional.empty();
        }
        try {
            return Optional.of((ref.mediaType() == ArtifactMediaType.OPENAPI_YAML ? YAML : JSON).readTree(bytes));
        } catch (IOException malformed) {
            ErrorCode code = ref.mediaType() == ArtifactMediaType.SCHEMA_JSON ? ErrorCode.SCHEMA_DOCUMENT_INVALID : ErrorCode.API_DOCUMENT_INVALID;
            diagnostics.add(Diagnostic.error(code, ref.path() + " does not parse as " + ref.mediaType().wireValue(), at));
            return Optional.empty();
        }
    }
}
