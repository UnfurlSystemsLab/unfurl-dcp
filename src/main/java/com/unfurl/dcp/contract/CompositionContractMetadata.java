package com.unfurl.dcp.contract;

import java.net.URI;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema record: extension metadata for composition contracts.
 *
 * <p>Pattern: Composite child-reference adapter. It reuses the recursive DCP containment bridge
 * from claims so aggregate contracts can reference child composition contracts without using
 * private planner metadata as the governed closure.
 */
public record CompositionContractMetadata(Map<String, Object> extensions) {
    public static final String EXT_CONTAINS = "contains";
    public static final String EXT_CHILDREN = "children";
    public static final String EXT_CONTAINS_CLAIM_URIS = "containsClaimUris";
    public static final String EXT_CHILD_CLAIM_URIS = "childClaimUris";

    /**
     * Defensive-copy constructor: keeps extension metadata immutable and treats missing metadata
     * as an empty extension map.
     */
    public CompositionContractMetadata {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    /**
     * Extract child contract ids from all supported containment extension keys.
     *
     * <p>Accepted values mirror claim projection: a URI string, a list of URI strings, or maps
     * containing {@code contractId}, {@code claimUri}, {@code uri}, or {@code ref}.
     *
     * @return deterministic, de-duplicated child contract ids.
     */
    public List<URI> childContractIds() {
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
     * Adapter helper: normalizes the loose extension shape into URI values.
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
                Object value = firstPresent(map, "contractId", "claimUri", "uri", "ref");
                if (value instanceof String text && !text.isBlank()) {
                    result.add(URI.create(text));
                }
            }
        }
    }

    /**
     * Map lookup helper: adds {@code contractId} to the established recursive-DCP ref key set.
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
