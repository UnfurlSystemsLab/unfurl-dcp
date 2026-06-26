package com.unfurl.dcp.projection;

import java.net.URI;
import java.util.List;

public record DcpProjection(
        URI rootClaimUri,
        URI focusClaimUri,
        List<DcpProjectionNode> nodes,
        List<DcpProjectionEdge> edges,
        List<String> warnings
) {
    public DcpProjection {
        if (rootClaimUri == null) {
            throw new IllegalArgumentException("rootClaimUri is required");
        }
        focusClaimUri = focusClaimUri == null ? rootClaimUri : focusClaimUri;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
