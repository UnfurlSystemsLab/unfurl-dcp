package com.unfurl.dcp.projection;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.Offer;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DcpProjectionProjector {
    public static final String EXT_CONTAINS = "contains";
    public static final String EXT_CHILDREN = "children";
    public static final String EXT_CONTAINS_CLAIM_URIS = "containsClaimUris";
    public static final String EXT_CHILD_CLAIM_URIS = "childClaimUris";
    public static final String RELATIONSHIP_CONTAINS = "CONTAINS";

    public DcpProjection project(DcpProjectionRequest request) {
        URI rootUri = request.currentClaim().identity().uri();
        ProjectionState state = new ProjectionState(request);
        walk(rootUri, null, 0, new ArrayDeque<>(), state);

        URI focus = state.nodes.containsKey(request.focusClaimUri())
                ? request.focusClaimUri()
                : rootUri;
        if (!focus.equals(request.focusClaimUri())) {
            state.warn("focus claim is not present in projected subtree: " + request.focusClaimUri());
        }

        return new DcpProjection(
                rootUri,
                focus,
                state.nodes.values().stream()
                        .map(node -> node.withDescendants(state.descendantsByUri.getOrDefault(node.claimUri(), List.of())))
                        .toList(),
                state.edges,
                state.warnings);
    }

    private List<URI> walk(
            URI claimUri,
            URI parentClaimUri,
            int depth,
            ArrayDeque<URI> path,
            ProjectionState state
    ) {
        if (state.nodes.size() >= state.request.maxNodes()) {
            state.warn("projection node cap reached at " + state.request.maxNodes());
            return List.of();
        }
        if (depth > state.request.maxDepth()) {
            state.warn("projection depth cap reached at " + claimUri);
            return List.of();
        }
        if (path.contains(claimUri)) {
            state.warn("cycle skipped at " + claimUri);
            return List.of();
        }

        Claim claim = state.request.claimsByUri().get(claimUri);
        if (claim == null) {
            state.warn("missing contained claim: " + claimUri);
            return List.of();
        }

        boolean firstVisit = !state.nodes.containsKey(claimUri);
        if (firstVisit) {
            state.nodes.put(claimUri, ProjectedNode.from(claim, parentClaimUri, depth, levelFor(claim, depth)));
        }

        path.addLast(claimUri);
        LinkedHashSet<URI> descendants = new LinkedHashSet<>();
        for (URI childUri : childClaimUris(claim)) {
            if (!state.request.claimsByUri().containsKey(childUri)) {
                state.warn("contained claim is not loaded: " + childUri);
                continue;
            }
            state.edges.add(new DcpProjectionEdge(claimUri, childUri, RELATIONSHIP_CONTAINS));
            descendants.add(childUri);
            descendants.addAll(walk(childUri, claimUri, depth + 1, path, state));
        }
        path.removeLast();

        state.descendantsByUri.merge(claimUri, List.copyOf(descendants), DcpProjectionProjector::mergeDistinct);
        return List.copyOf(descendants);
    }

    private static List<URI> mergeDistinct(List<URI> left, List<URI> right) {
        LinkedHashSet<URI> merged = new LinkedHashSet<>(left);
        merged.addAll(right);
        return List.copyOf(merged);
    }

    private static String levelFor(Claim claim, int depth) {
        Object value = claim.metadata() == null ? null : claim.metadata().extensions().get("level");
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return depth == 0 ? "ROOT" : childClaimUris(claim).isEmpty() ? "LEAF" : "AGGREGATE";
    }

    private static List<URI> childClaimUris(Claim claim) {
        if (claim.metadata() == null) {
            return List.of();
        }
        Map<String, Object> extensions = claim.metadata().extensions();
        LinkedHashSet<URI> result = new LinkedHashSet<>();
        addUris(result, extensions.get(EXT_CONTAINS));
        addUris(result, extensions.get(EXT_CHILDREN));
        addUris(result, extensions.get(EXT_CONTAINS_CLAIM_URIS));
        addUris(result, extensions.get(EXT_CHILD_CLAIM_URIS));
        return result.stream()
                .sorted(Comparator.comparing(URI::toString))
                .toList();
    }

    private static void addUris(Set<URI> result, Object raw) {
        if (raw instanceof String text && !text.isBlank()) {
            result.add(URI.create(text));
            return;
        }
        if (!(raw instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) {
                result.add(URI.create(text));
            } else if (item instanceof Map<?, ?> map) {
                Object value = firstPresent(map, "claimUri", "uri", "ref");
                if (value instanceof String text && !text.isBlank()) {
                    result.add(URI.create(text));
                }
            }
        }
    }

    private static Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private record ProjectedNode(
            URI claimUri,
            String label,
            String dcpType,
            String level,
            URI parentClaimUri,
            int depth,
            List<String> offers
    ) {
        static ProjectedNode from(Claim claim, URI parentClaimUri, int depth, String level) {
            return new ProjectedNode(
                    claim.identity().uri(),
                    claim.identity().name(),
                    dcpTypeFor(claim),
                    level,
                    parentClaimUri,
                    depth,
                    claim.offers().stream()
                            .map(Offer::capability)
                            .sorted()
                            .toList());
        }

        DcpProjectionNode withDescendants(List<URI> descendants) {
            return new DcpProjectionNode(claimUri, label, dcpType, level, parentClaimUri, depth, descendants, offers);
        }
    }

    private static String dcpTypeFor(Claim claim) {
        Object value = claim.metadata() == null ? null : claim.metadata().extensions().get("dcpType");
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return claim.identity().kind().name();
    }

    private static final class ProjectionState {
        private final DcpProjectionRequest request;
        private final Map<URI, ProjectedNode> nodes = new LinkedHashMap<>();
        private final Map<URI, List<URI>> descendantsByUri = new LinkedHashMap<>();
        private final List<DcpProjectionEdge> edges = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final Set<String> warningKeys = new HashSet<>();

        private ProjectionState(DcpProjectionRequest request) {
            this.request = request;
        }

        private void warn(String warning) {
            if (warningKeys.add(warning)) {
                warnings.add(warning);
            }
        }
    }
}
