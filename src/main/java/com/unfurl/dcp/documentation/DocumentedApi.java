package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.ArtifactRef;

import java.util.List;
import java.util.Objects;

/**
 * Value Object: one projected product API document of a component (DCP HLD-C2 A.13, G.1).
 *
 * @param apiId          the document's id in the component's claim
 * @param openapi        the shipped OpenAPI document
 * @param visibility     PUBLIC or INTERNAL
 * @param servedSurfaces the document's surfaces that the host reports as served, sorted; only their operations render
 */
public record DocumentedApi(String apiId, ArtifactRef openapi, Visibility visibility, List<String> servedSurfaces) {
    /** Validating constructor: required fields and a sorted copy. */
    public DocumentedApi {
        Objects.requireNonNull(apiId, "apiId");
        Objects.requireNonNull(openapi, "openapi");
        if (visibility == null || visibility == Visibility.PRIVATE) {
            throw new IllegalArgumentException("a documented API is PUBLIC or INTERNAL");
        }
        servedSurfaces = servedSurfaces == null ? List.of() : servedSurfaces.stream().sorted().distinct().toList();
    }
}
