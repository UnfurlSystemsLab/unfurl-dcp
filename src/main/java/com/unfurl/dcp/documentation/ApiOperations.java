package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.unfurl.dcp.claim.ApiDocument;
import com.unfurl.dcp.claim.ArtifactMediaType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Utility: parses a shipped API document and lists its operations with their declared surface (DCP HLD-C2 A.13). The
 * projector uses it to report operations it does not render; the renderer uses {@link #parse} to merge documents.
 */
final class ApiOperations {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ApiOperations() { }

    /** One operation of an API document. */
    record Operation(String method, String path, String surface, String access) {
        /** The {@code <METHOD> <path>} label used in reports. */
        String label() {
            return method.toUpperCase(Locale.ROOT) + " " + path;
        }
    }

    /** Parses a document's verified bytes by its media type. */
    static JsonNode parse(ApiDocument document, byte[] bytes) {
        try {
            return (document.openapi().mediaType() == ArtifactMediaType.OPENAPI_YAML ? YAML : JSON).readTree(bytes);
        } catch (IOException malformed) {
            throw new DocumentationException(DocumentationException.ARTIFACT_INVALID,
                    document.openapi().path() + " does not parse; it was validated, so its bytes changed", malformed);
        }
    }

    /** Lists a parsed document's operations in path and method order. */
    static List<Operation> of(JsonNode root) {
        List<Operation> operations = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        root.path("paths").fieldNames().forEachRemaining(paths::add);
        paths.sort(String::compareTo);
        for (String path : paths) {
            JsonNode item = root.path("paths").path(path);
            ClaimArtifactValidator.METHODS.stream().sorted().forEach(method -> {
                JsonNode operation = item.get(method);
                if (operation != null) {
                    operations.add(new Operation(method, path, operation.path("x-dcp-surface").asText(),
                            operation.path("x-dcp-access").asText()));
                }
            });
        }
        return List.copyOf(operations);
    }
}
