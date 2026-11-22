package com.unfurl.dcp.documentation;

/**
 * Enumeration: documentation output kinds (DCP HLD-C2 G.2). {@link #ASYNCAPI}, {@link #MCP_TOOLS} and
 * {@link #SDK_METADATA} are reserved: no renderer exists until their offer kinds define documentation fields.
 */
public enum SurfaceKind {
    /** OpenAPI 3.1.0, one document per component. */
    OPENAPI,
    /** A Markdown summary covering every entry. */
    HUMAN_SUMMARY,
    /** Swagger UI over the OpenAPI documents (served by the embedding service). */
    SWAGGER_UI,
    /** Reserved. */
    ASYNCAPI,
    /** Reserved. */
    MCP_TOOLS,
    /** Reserved. */
    SDK_METADATA;

    /** Whether this kind is reserved and has no renderer. */
    public boolean reserved() {
        return this == ASYNCAPI || this == MCP_TOOLS || this == SDK_METADATA;
    }
}
