package com.unfurl.dcp.claim;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * Enumeration: the media types an {@link ArtifactRef} may name (DCP HLD-C2 A.12). The wire value is the media-type string
 * itself, not the constant name, so claims read the way the specification writes them.
 *
 * <p>Which field accepts which type is a claim rule: offer payload schemas accept only {@link #SCHEMA_JSON}, and API
 * documents accept only the two OpenAPI types ({@link ClaimValidator}).
 */
public enum ArtifactMediaType {
    /** A JSON Schema (draft 2020-12) document. */
    SCHEMA_JSON("application/schema+json"),
    /** An OpenAPI 3.1.0 document encoded as JSON. */
    OPENAPI_JSON("application/vnd.oai.openapi+json"),
    /** An OpenAPI 3.1.0 document encoded as YAML. */
    OPENAPI_YAML("application/vnd.oai.openapi+yaml");

    private final String wireValue;

    /** Constructor: binds the constant to its media-type string. */
    ArtifactMediaType(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The media-type string written on the wire and into the canonical claim hash. */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /** Whether this is one of the OpenAPI media types. */
    public boolean isOpenApi() {
        return this == OPENAPI_JSON || this == OPENAPI_YAML;
    }

    /**
     * Factory: reads a media-type string. Matching is exact, because a media type in a pinned claim is part of what the
     * hash freezes.
     *
     * @throws IllegalArgumentException for any other value, which Jackson reports as a malformed claim
     */
    @JsonCreator
    public static ArtifactMediaType fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported artifact media type: " + value));
    }
}
