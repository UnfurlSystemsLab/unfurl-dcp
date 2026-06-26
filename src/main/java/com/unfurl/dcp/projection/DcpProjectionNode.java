package com.unfurl.dcp.projection;

import java.net.URI;
import java.util.List;

public record DcpProjectionNode(
        URI claimUri,
        String label,
        String dcpType,
        String level,
        URI parentClaimUri,
        int depth,
        List<URI> descendantClaimUris,
        List<String> offers
) {
    public DcpProjectionNode {
        if (claimUri == null) {
            throw new IllegalArgumentException("claimUri is required");
        }
        label = label == null || label.isBlank() ? claimUri.toString() : label;
        dcpType = dcpType == null || dcpType.isBlank() ? "COMPONENT" : dcpType;
        level = level == null || level.isBlank() ? "LEAF" : level;
        depth = Math.max(0, depth);
        descendantClaimUris = descendantClaimUris == null ? List.of() : List.copyOf(descendantClaimUris);
        offers = offers == null ? List.of() : List.copyOf(offers);
    }
}
