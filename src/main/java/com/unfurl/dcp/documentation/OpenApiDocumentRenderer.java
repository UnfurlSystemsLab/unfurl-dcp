package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unfurl.dcp.claim.ApiDocument;
import com.unfurl.dcp.claim.ArtifactMediaType;
import com.unfurl.dcp.claim.ArtifactRef;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Renderer (Strategy): one OpenAPI 3.1.0 document per documented component (DCP HLD-C2 G.2).
 *
 * <p>Each document holds:
 * <ul>
 *   <li><b>DCP invocation operations</b>, one {@code POST /dcp/<capability>} per HTTP capability. The request body is
 *       the operation's envelope, or a {@code oneOf} of per-operation envelopes fixed by {@code const} operation. The
 *       {@code 200} response is each operation's result, {@code oneOf} its success branch and the shared failure branch,
 *       or an {@code anyOf} of those results when there are several operations. {@code x-dcp-operations} maps each
 *       operation to its pair.</li>
 *   <li><b>The registration report operation</b>, {@code GET /dcp/registrations}, in every document: every documented
 *       component is a DCP HTTP host, and every host serves its report (G.3). Its {@code 200} response is the
 *       renderer-written {@code dcp.registrations.report} schema; it is secured like the invocation operations and
 *       names {@code x-dcp-permission: dcp.registrations.read} when it is.</li>
 *   <li><b>Product API operations</b> from the shipped documents, filtered to served surfaces, with their components
 *       renamed {@code <api_id>.<name>} and every reference rewritten.</li>
 *   <li><b>Hosted capabilities</b> under {@code x-dcp-hosted-capabilities}, with schemas embedded under
 *       {@code hosted.<provider slug>.}; no path is generated for them.</li>
 * </ul>
 * Shipped schemas are embedded with their JSON-Pointer references rebased, and a final pass resolves every reference in
 * the output ({@code RENDER_REFERENCE_UNRESOLVED} otherwise). Security is per operation and never document-wide.
 * Output order is fixed, so equal inputs give byte-equal documents.
 */
public final class OpenApiDocumentRenderer implements DocumentationRenderer<Map<URI, ObjectNode>> {
    /** The security scheme name. */
    public static final String BEARER = "bearerAuth";
    /** The shared failure branch of every result. */
    public static final String FAILURE = "dcp.invocation.failure";
    /** The caller context of an unprotected component's envelope. */
    public static final String CONTEXT = "dcp.invocation.context";
    /** The registration report route every DCP HTTP host serves (HLD-C2 G.3). */
    public static final String REGISTRATIONS_PATH = "/dcp/registrations";
    /** The registration report schema, written once per document. */
    public static final String REGISTRATION_REPORT = "dcp.registrations.report";
    /** The permission the registration report needs under an identity contract (HLD-C2 G.3). */
    public static final String REGISTRATIONS_PERMISSION = "dcp.registrations.read";
    private static final String HASH_PATTERN = "^[0-9a-f]{64}$";
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());

    /** Renders every component's document, keyed by component URI in URI order. */
    @Override
    public Map<URI, ObjectNode> render(CapabilityDocumentation documentation, DocumentationArtifactStore artifacts) {
        Map<URI, ObjectNode> documents = new TreeMap<>(Comparator.comparing(URI::toString));
        for (DocumentedComponent component : documentation.components()) {
            documents.put(component.componentUri(), document(component, documentation.identity(), artifacts));
        }
        return Collections.unmodifiableMap(documents);
    }

    /** One component's document. */
    private ObjectNode document(DocumentedComponent component, IdentityMode identity, DocumentationArtifactStore artifacts) {
        boolean secured = identity == IdentityMode.IN_CONTRACT && component.protectedComponent();
        Schemas schemas = new Schemas();
        Map<String, ObjectNode> paths = new TreeMap<>();
        Map<String, Map<String, JsonNode>> productComponents = new TreeMap<>();
        ArrayNode hosted = NODES.arrayNode();
        boolean anyHttp = false;

        for (DocumentedCapability capability : component.capabilities()) {
            if (capability.surface() == EntrySurface.HTTP) {
                anyHttp = true;
                paths.put(capability.path(), dcpPathItem(capability, secured, schemas, artifacts));
            } else if (capability.surface() == EntrySurface.HOSTED_IN_PROCESS) {
                hosted.add(hostedItem(capability, schemas, artifacts));
            }
        }
        if (anyHttp) {
            schemas.put(FAILURE, failureSchema());
            if (!secured) schemas.put(CONTEXT, contextSchema());
        }
        // Every documented component is a DCP HTTP host, so its report route is documented here too (G.2). The
        // capability name is reserved by claim validation; this guard keeps an unvalidated input from shadowing it.
        if (paths.containsKey(REGISTRATIONS_PATH)) {
            throw new DocumentationException(DocumentationException.API_DOCUMENT_PATH_CONFLICT,
                    "capability registrations is reserved by the DCP HTTP binding for " + REGISTRATIONS_PATH);
        }
        paths.put(REGISTRATIONS_PATH, registrationsPathItem(secured));
        schemas.put(REGISTRATION_REPORT, registrationReportSchema());
        for (DocumentedApi api : component.apiDocuments()) {
            mergeApi(api, secured, paths, productComponents, schemas, artifacts);
        }

        ObjectNode root = NODES.objectNode();
        root.put("openapi", "3.1.0");
        ObjectNode info = root.putObject("info");
        info.put("title", component.name());
        info.put("version", component.componentVersion());
        info.put("description", description(identity, secured));
        info.put("x-dcp-component", component.componentUri().toString());
        info.put("x-dcp-claim-hash", component.claimHash());
        root.putArray("servers").addObject().put("url", component.serviceRoot().toString());
        ObjectNode pathsNode = root.putObject("paths");
        paths.forEach(pathsNode::set);
        ObjectNode componentsNode = root.putObject("components");
        ObjectNode schemaNode = componentsNode.putObject("schemas");
        schemas.all().forEach(schemaNode::set);
        productComponents.forEach((section, entries) -> {
            if (section.equals("schemas")) return;
            ObjectNode sectionNode = componentsNode.putObject(section);
            entries.forEach(sectionNode::set);
        });
        if (secured) {
            ObjectNode bearer = componentsNode.putObject("securitySchemes").putObject(BEARER);
            bearer.put("type", "http");
            bearer.put("scheme", "bearer");
            bearer.put("bearerFormat", "JWT");
        }
        if (!hosted.isEmpty()) {
            root.set("x-dcp-hosted-capabilities", hosted);
        }
        guard(root);
        return root;
    }

    /** The {@code POST /dcp/<capability>} path item of an HTTP capability. */
    private ObjectNode dcpPathItem(DocumentedCapability capability, boolean secured, Schemas schemas, DocumentationArtifactStore artifacts) {
        String base = Slugs.componentName(capability.capability());
        List<String> envelopes = new ArrayList<>();
        List<String> results = new ArrayList<>();
        ObjectNode operationMap = NODES.objectNode();
        for (DocumentedOperation operation : capability.operations()) {
            String name = base + "." + Slugs.componentName(operation.operation());
            String request = name + ".request";
            String response = name + ".response";
            schemas.put(request, embed(artifacts, operation.requestSchema(), request));
            schemas.put(response, embed(artifacts, operation.responseSchema(), response));
            schemas.put(name + ".envelope", envelope(capability, operation.operation(), request, !secured));
            schemas.put(name + ".success", success(response));
            ObjectNode result = NODES.objectNode();
            result.putArray("oneOf").add(ref(name + ".success")).add(ref(FAILURE));
            result.put("x-dcp-operation", operation.operation());
            schemas.put(name + ".result", result);
            envelopes.add(name + ".envelope");
            results.add(name + ".result");
            ObjectNode pair = operationMap.putObject(operation.operation());
            pair.put("request", "#/components/schemas/" + JsonReferences.escape(name + ".envelope"));
            pair.put("response", "#/components/schemas/" + JsonReferences.escape(name + ".result"));
            if (operation.summary() != null) pair.put("summary", operation.summary());
        }
        ObjectNode post = NODES.objectNode();
        post.put("operationId", "dcp." + base);
        post.put("summary", capability.description() == null ? capability.capability() : capability.description());
        post.putArray("tags").add("dcp");
        post.put("x-dcp-capability", capability.capability());
        post.put("x-dcp-contract", capability.contractId().toString());
        ArrayNode faults = post.putArray("x-dcp-faults");
        capability.faults().forEach(faults::add);
        post.set("x-dcp-operations", operationMap);
        if (secured) post.putArray("security").addObject().putArray(BEARER);
        ObjectNode body = post.putObject("requestBody");
        body.put("required", true);
        body.putObject("content").putObject("application/json").set("schema", choice(envelopes, "oneOf"));
        ObjectNode responses = post.putObject("responses");
        ObjectNode ok = responses.putObject("200");
        ok.put("description", "The capability's result: a success carrying the operation's output, or a capability fault"
                + " (success false) carrying a declared fault code.");
        ok.putObject("content").putObject("application/json").set("schema", choice(results, "anyOf"));
        responses.putObject("400").put("description", secured
                ? "Malformed envelope, or a body context while the composition has an identity contract."
                : "Malformed envelope.");
        if (secured) {
            responses.putObject("401").put("description", "Missing or invalid bearer token.");
            responses.putObject("403").put("description", "The token lacks the required permission.");
        }
        responses.putObject("404").put("description", "The capability is not registered on this host.");
        responses.putObject("503").put("description", "The host is not ready.");
        ObjectNode item = NODES.objectNode();
        item.set("post", post);
        return item;
    }

    /**
     * The {@code GET /dcp/registrations} path item (HLD-C2 G.2, registration report operation). It is secured exactly
     * like the component's invocation operations: under an identity contract that protects the component it needs a
     * bearer token with {@link #REGISTRATIONS_PERMISSION}; otherwise it has no security.
     */
    private static ObjectNode registrationsPathItem(boolean secured) {
        ObjectNode get = NODES.objectNode();
        get.put("operationId", "dcp.registrations");
        get.put("summary", "What this host has registered right now.");
        get.put("description", "The DCP registration report (DCP HTTP Binding section 4): the host's identity and claim"
                + " hash, every capability registered right now with its provider evidence, and the product surfaces this"
                + " instance serves. It lists contracts only: no inputs, outputs, credentials or caller data.");
        get.putArray("tags").add("dcp");
        if (secured) {
            get.putArray("security").addObject().putArray(BEARER);
            get.put("x-dcp-permission", REGISTRATIONS_PERMISSION);
        }
        ObjectNode responses = get.putObject("responses");
        ObjectNode ok = responses.putObject("200");
        ok.put("description", "The host's current registration report.");
        ObjectNode cacheControl = ok.putObject("headers").putObject("Cache-Control");
        cacheControl.put("description", "Always no-store: the report states the host's registrations at this moment.");
        cacheControl.putObject("schema").put("type", "string").put("const", "no-store");
        ok.putObject("content").putObject("application/json").set("schema", ref(REGISTRATION_REPORT));
        if (secured) {
            responses.putObject("401").put("description", "Missing or invalid bearer token.");
            responses.putObject("403").put("description", "The token lacks " + REGISTRATIONS_PERMISSION + ".");
        }
        ObjectNode item = NODES.objectNode();
        item.set("get", get);
        return item;
    }

    /**
     * The registration report schema (HLD-C2 G.3, {@code registration-report.schema.yaml}) in JSON Schema 2020-12. It
     * describes exactly what {@link RegistrationReportCodec} writes: snake_case fields, lower-case surfaces, and
     * {@code path} required for an HTTP registration. Unknown fields are allowed, because readers ignore them and a
     * newer host's report stays valid.
     */
    private static ObjectNode registrationReportSchema() {
        ObjectNode report = NODES.objectNode();
        report.put("type", "object");
        report.put("description", "What a DCP HTTP host has registered right now (DCP HTTP Binding section 4).");
        report.putArray("required").add("component_uri").add("component_version").add("claim_hash").add("observed_at")
                .add("registrations").add("served_surfaces");
        ObjectNode properties = report.putObject("properties");
        properties.putObject("component_uri").put("type", "string").put("format", "uri");
        properties.putObject("component_version").put("type", "string");
        properties.putObject("claim_hash").put("type", "string").put("pattern", HASH_PATTERN);
        properties.putObject("observed_at").put("type", "string").put("format", "date-time");
        ObjectNode registrations = properties.putObject("registrations");
        registrations.put("type", "array");
        ObjectNode registration = registrations.putObject("items");
        registration.put("type", "object");
        registration.putArray("required").add("contract_id").add("contract_version").add("capability")
                .add("provider_claim_uri").add("provider_claim_version").add("provider_claim_hash").add("surface");
        ObjectNode fields = registration.putObject("properties");
        fields.putObject("contract_id").put("type", "string").put("format", "uri");
        fields.putObject("contract_version").put("type", "string");
        fields.putObject("capability").put("type", "string");
        fields.putObject("provider_claim_uri").put("type", "string").put("format", "uri");
        fields.putObject("provider_claim_version").put("type", "string");
        fields.putObject("provider_claim_hash").put("type", "string").put("pattern", HASH_PATTERN);
        fields.putObject("surface").putArray("enum")
                .add(EntrySurface.HTTP.wireValue()).add(EntrySurface.HOSTED_IN_PROCESS.wireValue());
        fields.putObject("path").put("type", "string").put("pattern", "^/dcp/.+$");
        registration.putObject("if").putObject("properties").putObject("surface").put("const", EntrySurface.HTTP.wireValue());
        registration.putObject("then").putArray("required").add("path");
        ObjectNode surfaces = properties.putObject("served_surfaces");
        surfaces.put("type", "array");
        surfaces.putObject("items").put("type", "string");
        return report;
    }

    /** One item of {@code x-dcp-hosted-capabilities}, with its schemas embedded under a provider prefix. */
    private ObjectNode hostedItem(DocumentedCapability capability, Schemas schemas, DocumentationArtifactStore artifacts) {
        String prefix = "hosted." + Slugs.of(capability.providerComponentUri()) + "." + Slugs.componentName(capability.capability());
        ObjectNode item = NODES.objectNode();
        item.put("capability", capability.capability());
        item.put("provider_component_uri", capability.providerComponentUri().toString());
        item.put("provider_claim_version", capability.providerComponentVersion());
        if (capability.description() != null) item.put("description", capability.description());
        item.put("contract", capability.contractId().toString());
        ArrayNode faults = item.putArray("faults");
        capability.faults().forEach(faults::add);
        ArrayNode operations = item.putArray("operations");
        for (DocumentedOperation operation : capability.operations()) {
            String name = prefix + "." + Slugs.componentName(operation.operation());
            schemas.put(name + ".request", embed(artifacts, operation.requestSchema(), name + ".request"));
            schemas.put(name + ".response", embed(artifacts, operation.responseSchema(), name + ".response"));
            ObjectNode entry = operations.addObject();
            entry.put("operation", operation.operation());
            if (operation.summary() != null) entry.put("summary", operation.summary());
            entry.set("request", ref(name + ".request"));
            entry.set("response", ref(name + ".response"));
        }
        return item;
    }

    /** Merges one product API document: served operations, renamed components, rewritten references, security. */
    private void mergeApi(DocumentedApi api, boolean secured, Map<String, ObjectNode> paths,
                          Map<String, Map<String, JsonNode>> productComponents, Schemas schemas, DocumentationArtifactStore artifacts) {
        byte[] bytes = artifacts.read(api.openapi());
        JsonNode source;
        try {
            source = (api.openapi().mediaType() == ArtifactMediaType.OPENAPI_YAML ? YAML : JSON).readTree(bytes);
        } catch (IOException malformed) {
            throw new DocumentationException(DocumentationException.ARTIFACT_INVALID, api.openapi().path() + " does not parse", malformed);
        }
        String prefix = api.apiId() + ".";
        JsonNode rewritten = JsonReferences.prefixComponentRefs(source, api.apiId());
        rewritten.path("components").fields().forEachRemaining(section -> {
            if (!ClaimArtifactValidator.COMPONENT_SECTIONS.contains(section.getKey())) return;
            List<String> names = new ArrayList<>();
            section.getValue().fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                String renamed = prefix + name;
                if (section.getKey().equals("schemas")) {
                    schemas.put(renamed, section.getValue().get(name));
                } else if (productComponents.computeIfAbsent(section.getKey(), key -> new TreeMap<>())
                        .putIfAbsent(renamed, section.getValue().get(name)) != null) {
                    throw new DocumentationException(DocumentationException.SCHEMA_NAME_COLLISION,
                            "components." + section.getKey() + "." + renamed + " is declared twice");
                }
            }
        });
        Set<String> served = Set.copyOf(api.servedSurfaces());
        List<String> pathNames = new ArrayList<>();
        rewritten.path("paths").fieldNames().forEachRemaining(pathNames::add);
        pathNames.sort(String::compareTo);
        for (String path : pathNames) {
            JsonNode item = rewritten.path("paths").path(path);
            ObjectNode out = NODES.objectNode();
            item.fields().forEachRemaining(member -> {
                if (!ClaimArtifactValidator.METHODS.contains(member.getKey())) {
                    if (member.getKey().equals("parameters")) return;
                    out.set(member.getKey(), member.getValue());
                    return;
                }
                if (!served.contains(member.getValue().path("x-dcp-surface").asText())) return;
                ObjectNode operation = member.getValue().deepCopy();
                inheritParameters(item, operation, productComponents);
                if (secured) {
                    if ("protected".equals(operation.path("x-dcp-access").asText())) {
                        operation.putArray("security").addObject().putArray(BEARER);
                        ObjectNode responses = operation.has("responses") ? (ObjectNode) operation.get("responses") : operation.putObject("responses");
                        if (!responses.has("401")) responses.putObject("401").put("description", "Missing or invalid bearer token.");
                        if (!responses.has("403")) responses.putObject("403").put("description", "The token lacks the required permission.");
                    } else {
                        operation.putArray("security");
                    }
                }
                out.set(member.getKey(), operation);
            });
            boolean hasOperation = false;
            for (String method : ClaimArtifactValidator.METHODS) hasOperation |= out.has(method);
            if (hasOperation) mergePath(path, out, paths);
        }
    }

    /** Merger: retains disjoint methods and equal shared metadata, refusing conflicts instead of overwriting them. */
    private static void mergePath(String path, ObjectNode incoming, Map<String, ObjectNode> paths) {
        ObjectNode existing = paths.computeIfAbsent(path, key -> NODES.objectNode());
        incoming.fields().forEachRemaining(field -> {
            JsonNode previous = existing.get(field.getKey());
            if (previous != null && (ClaimArtifactValidator.METHODS.contains(field.getKey())
                    || !previous.equals(field.getValue()))) {
                throw new DocumentationException(DocumentationException.API_DOCUMENT_PATH_CONFLICT,
                        "Conflicting path member " + path + "/" + field.getKey());
            }
            existing.set(field.getKey(), field.getValue());
        });
    }

    /** Projector: scopes path parameters to their source operation, preserving operation-level overrides by name/in. */
    private static void inheritParameters(JsonNode item, ObjectNode operation,
                                          Map<String, Map<String, JsonNode>> components) {
        if (!item.has("parameters")) return;
        Map<String, JsonNode> effective = new LinkedHashMap<>();
        item.path("parameters").forEach(parameter -> effective.put(parameterKey(parameter, components), parameter));
        operation.path("parameters").forEach(parameter -> effective.put(parameterKey(parameter, components), parameter));
        ArrayNode parameters = operation.putArray("parameters");
        effective.values().forEach(parameters::add);
    }

    /** Resolver: identifies inline or referenced parameters by their OpenAPI name/in identity, rejecting cycles. */
    private static String parameterKey(JsonNode parameter, Map<String, Map<String, JsonNode>> components) {
        JsonNode resolved = parameter;
        Set<String> seen = new HashSet<>();
        while (resolved.has("$ref")) {
            String reference = resolved.path("$ref").asText();
            if (!seen.add(reference)) {
                throw new DocumentationException(DocumentationException.API_DOCUMENT_PATH_CONFLICT,
                        "Cyclic parameter reference " + reference);
            }
            ObjectNode root = NODES.objectNode();
            ObjectNode sections = root.putObject("components");
            components.forEach((section, entries) -> {
                ObjectNode members = sections.putObject(section);
                entries.forEach(members::set);
            });
            resolved = root.at(URI.create(reference).getFragment());
        }
        if (!resolved.path("name").isTextual() || !resolved.path("in").isTextual()) {
            throw new DocumentationException(DocumentationException.API_DOCUMENT_PATH_CONFLICT,
                    "A path parameter requires a name and location");
        }
        return resolved.path("in").asText() + ":" + resolved.path("name").asText();
    }

    /** Parses one shipped schema and rebases its JSON-Pointer references under {@code #/components/schemas/<name>}. */
    private static JsonNode embed(DocumentationArtifactStore artifacts, ArtifactRef ref, String name) {
        JsonNode schema;
        try {
            schema = JSON.readTree(artifacts.read(ref));
        } catch (IOException malformed) {
            throw new DocumentationException(DocumentationException.ARTIFACT_INVALID, ref.path() + " does not parse", malformed);
        }
        String base = "#/components/schemas/" + JsonReferences.escape(name);
        return JsonReferences.rewriteRefs(schema, value -> value.startsWith("#") ? base + value.substring(1) : value);
    }

    /** The request envelope of one operation, with the operation fixed by {@code const}. */
    private static ObjectNode envelope(DocumentedCapability capability, String operation, String requestSchema, boolean contextAllowed) {
        ObjectNode envelope = NODES.objectNode();
        envelope.put("type", "object");
        envelope.putArray("required").add("invocation");
        ObjectNode properties = envelope.putObject("properties");
        ObjectNode invocation = properties.putObject("invocation");
        invocation.put("type", "object");
        invocation.putArray("required").add("contractId").add("operation").add("input");
        ObjectNode fields = invocation.putObject("properties");
        fields.putObject("contractId").put("type", "string").put("const", capability.contractId().toString());
        fields.putObject("operation").put("const", operation);
        fields.putObject("consumerComponent").put("type", "string");
        fields.putObject("providerComponent").put("type", "string");
        fields.set("input", ref(requestSchema));
        fields.putObject("correlationId").put("type", "string");
        fields.putObject("traceContext").put("type", "object").putObject("additionalProperties").put("type", "string");
        fields.putObject("integrityHash").put("type", "string");
        fields.putObject("metadata").put("type", "object");
        // A body context is accepted only when the component is not protected; with identity it is rejected (G.3).
        if (contextAllowed) properties.set("context", ref(CONTEXT));
        envelope.put("additionalProperties", false);
        return envelope;
    }

    /** The success branch of one operation's result. */
    private static ObjectNode success(String responseSchema) {
        ObjectNode success = NODES.objectNode();
        success.put("type", "object");
        success.putArray("required").add("success").add("output");
        ObjectNode properties = success.putObject("properties");
        properties.putObject("success").put("const", true);
        properties.set("output", ref(responseSchema));
        properties.putObject("errorCode").put("type", "string");
        properties.putObject("errorMessage").put("type", "string");
        properties.putObject("metadata").put("type", "object");
        return success;
    }

    /** The shared failure branch: the response schema is never applied to a fault. */
    private static ObjectNode failureSchema() {
        ObjectNode failure = NODES.objectNode();
        failure.put("type", "object");
        failure.put("description", "A capability fault. errorCode is a declared fault code (x-dcp-faults) or a host failure"
                + " code; output is any object, normally {}.");
        failure.putArray("required").add("success").add("errorCode");
        ObjectNode properties = failure.putObject("properties");
        properties.putObject("success").put("const", false);
        properties.putObject("errorCode").put("type", "string");
        properties.putObject("errorMessage").put("type", "string");
        properties.putObject("output").put("type", "object");
        properties.putObject("metadata").put("type", "object");
        return failure;
    }

    /** The caller context accepted only when the composition has no identity contract for this component. */
    private static ObjectNode contextSchema() {
        ObjectNode context = NODES.objectNode();
        context.put("type", "object");
        ObjectNode properties = context.putObject("properties");
        for (String text : List.of("tenantId", "userId", "correlationId", "requestId")) {
            properties.putObject(text).put("type", "string");
        }
        for (String list : List.of("roles", "permissions")) {
            properties.putObject(list).put("type", "array").putObject("items").put("type", "string");
        }
        properties.putObject("traceContext").put("type", "object").putObject("additionalProperties").put("type", "string");
        properties.putObject("metadata").put("type", "object");
        return context;
    }

    /** A single reference, or a {@code oneOf}/{@code anyOf} of several. */
    private static JsonNode choice(List<String> names, String combinator) {
        if (names.size() == 1) return ref(names.get(0));
        ObjectNode choice = NODES.objectNode();
        ArrayNode options = choice.putArray(combinator);
        names.forEach(name -> options.add(ref(name)));
        return choice;
    }

    /** A {@code $ref} to a component schema. */
    private static ObjectNode ref(String name) {
        ObjectNode ref = NODES.objectNode();
        ref.put("$ref", "#/components/schemas/" + JsonReferences.escape(name));
        return ref;
    }

    /** The document's statement of its identity mode. */
    private static String description(IdentityMode identity, boolean secured) {
        if (identity == IdentityMode.ABSENT) {
            return "This composition has no identity contract: every operation is served without authentication on a"
                    + " trusted network.";
        }
        return secured
                ? "Protected by the composition's identity contract: operations marked with bearerAuth need an OAuth 2.0"
                + " bearer token; operations with an empty security list are public."
                : "The composition's identity contract does not protect this component: its operations need no token.";
    }

    /** The render guard: every reference in the output resolves. */
    private static void guard(ObjectNode root) {
        JsonReferences.forEachRef(root, (pointer, value) -> {
            if (!JsonReferences.resolves(root, value)) {
                throw new DocumentationException(DocumentationException.RENDER_REFERENCE_UNRESOLVED,
                        "$ref " + value + " at " + pointer + " does not resolve");
            }
        });
    }

    /** Component schemas in name order, refusing duplicates. */
    private static final class Schemas {
        private final Map<String, JsonNode> byName = new TreeMap<>();

        void put(String name, JsonNode schema) {
            if (byName.putIfAbsent(name, schema) != null) {
                throw new DocumentationException(DocumentationException.SCHEMA_NAME_COLLISION,
                        "components.schemas." + name + " would be declared twice");
            }
        }

        Map<String, JsonNode> all() {
            return byName;
        }
    }
}
