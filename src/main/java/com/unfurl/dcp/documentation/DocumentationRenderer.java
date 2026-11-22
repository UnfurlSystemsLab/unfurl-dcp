package com.unfurl.dcp.documentation;

/**
 * Strategy: renders one view of a projection (DCP HLD-C2 G.2). Every renderer is a pure function of the projection and
 * the verified artifacts captured with it, so equal inputs give equal outputs and rendering does no I/O.
 *
 * @param <T> the rendered form
 */
@FunctionalInterface
public interface DocumentationRenderer<T> {
    /**
     * Renders a view.
     *
     * @param documentation the projection
     * @param artifacts     the verified bytes it references ({@link DocumentationPlan#artifacts()} or
     *                      {@link ProjectedDocumentation#artifacts()})
     */
    T render(CapabilityDocumentation documentation, DocumentationArtifactStore artifacts);

    /**
     * Guard for reserved output kinds (AsyncAPI, MCP tools, SDK metadata): they have no renderer until their offer kinds
     * define documentation fields.
     *
     * @throws DocumentationException {@code UNSUPPORTED_SURFACE} for a reserved kind
     */
    static void requireRenderable(SurfaceKind kind) {
        if (kind.reserved()) {
            throw new DocumentationException(DocumentationException.UNSUPPORTED_SURFACE,
                    kind + " is a reserved documentation output with no renderer");
        }
    }
}
