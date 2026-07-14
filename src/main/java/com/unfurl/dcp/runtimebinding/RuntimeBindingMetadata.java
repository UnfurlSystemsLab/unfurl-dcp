package com.unfurl.dcp.runtimebinding;

import java.net.URI;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema record: extension metadata for runtime bindings.
 *
 * <p>Pattern: Composite child-reference adapter. It reuses the recursive DCP containment bridge
 * from claims so aggregate runtime bindings can reference child binding ids without inventing
 * product-specific closure sections.
 */
public record RuntimeBindingMetadata(Map<String, Object> extensions) {
    public static final String EXT_CONTAINS = "contains";
    public static final String EXT_CHILDREN = "children";
    public static final String EXT_CONTAINS_CLAIM_URIS = "containsClaimUris";
    public static final String EXT_CHILD_CLAIM_URIS = "childClaimUris";

    /**
     * Defensive-copy constructor: keeps metadata immutable at the map boundary while treating a
     * missing extension map as empty metadata.
     */
    public RuntimeBindingMetadata {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    /**
     * Extract child binding ids from all supported containment extension keys.
     *
     * <p>Accepted values mirror claim projection: a URI string, a list of URI strings, or maps
     * containing {@code bindingId}, {@code claimUri}, {@code uri}, or {@code ref}.
     *
     * @return deterministic, de-duplicated child binding ids.
     */
    public List<URI> childBindingIds() {
        LinkedHashSet<URI> result = new LinkedHashSet<>();
        addUris(result, extensions.get(EXT_CONTAINS));
        addUris(result, extensions.get(EXT_CHILDREN));
        addUris(result, extensions.get(EXT_CONTAINS_CLAIM_URIS));
        addUris(result, extensions.get(EXT_CHILD_CLAIM_URIS));
        return result.stream()
                .sorted(Comparator.comparing(URI::toString))
                .toList();
    }

    /**
     * Adapter helper: consumes the loose extension shape used by DCP documents and records only
     * syntactically non-empty URI values.
     */
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
                Object value = firstPresent(map, "bindingId", "claimUri", "uri", "ref");
                if (value instanceof String text && !text.isBlank()) {
                    result.add(URI.create(text));
                }
            }
        }
    }

    /**
     * Map lookup helper: preserves the established claim-containment compatibility behavior while
     * adding {@code bindingId} as the preferred key for runtime binding children.
     */
    private static Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }
}
