package com.unfurl.dcp.projection;

import com.unfurl.dcp.claim.Claim;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public record DcpProjectionRequest(
        Claim currentClaim,
        Map<URI, Claim> claimsByUri,
        URI focusClaimUri,
        int maxDepth,
        int maxNodes
) {
    public static final int DEFAULT_MAX_DEPTH = 16;
    public static final int DEFAULT_MAX_NODES = 512;

    public DcpProjectionRequest {
        if (currentClaim == null) {
            throw new IllegalArgumentException("currentClaim is required");
        }
        Map<URI, Claim> safeClaims = new LinkedHashMap<>();
        if (claimsByUri != null) {
            safeClaims.putAll(claimsByUri);
        }
        safeClaims.putIfAbsent(currentClaim.identity().uri(), currentClaim);
        claimsByUri = Map.copyOf(safeClaims);
        focusClaimUri = focusClaimUri == null ? currentClaim.identity().uri() : focusClaimUri;
        maxDepth = maxDepth <= 0 ? DEFAULT_MAX_DEPTH : maxDepth;
        maxNodes = maxNodes <= 0 ? DEFAULT_MAX_NODES : maxNodes;
    }
}
