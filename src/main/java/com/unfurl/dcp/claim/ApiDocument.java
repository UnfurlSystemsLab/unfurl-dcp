package com.unfurl.dcp.claim;

import java.util.List;

/**
 * Value Object: a shipped OpenAPI description of a component's product HTTP routes (DCP HLD-C2 A.13), the routes it serves
 * that are not DCP capability invocations.
 *
 * <p>An API document describes routes only. It never offers a capability, is never matched by the resolver and never
 * creates a contract. Its document-level rules (every operation tagged with {@code x-dcp-surface} and {@code
 * x-dcp-access}, no {@code /dcp/} paths, no servers or security, local references only) are checked by
 * {@code ClaimArtifactValidator}, which holds the bytes.
 *
 * @param apiId       kebab-case identifier, unique within the claim
 * @param description human-readable purpose
 * @param openapi     the OpenAPI 3.1.0 document; its media type must be an OpenAPI type
 * @param surfaces    the served surfaces the document covers; non-empty, kebab-case
 */
public record ApiDocument(String apiId, String description, ArtifactRef openapi, List<String> surfaces) {
    /** Defensive-copy constructor: an absent list is empty, so the validator reports it. */
    public ApiDocument {
        surfaces = surfaces == null ? List.of() : List.copyOf(surfaces);
    }
}
