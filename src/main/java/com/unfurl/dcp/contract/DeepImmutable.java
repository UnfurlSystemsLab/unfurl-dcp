package com.unfurl.dcp.contract;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deep defensive copy for the open-ended, JSON-shaped maps a contract carries ({@code Transport.details} and
 * {@code CompositionContractMetadata.extensions}). A shallow {@code Map.copyOf} leaves nested maps and lists shared and
 * mutable, so a holder of a verified contract could edit a nested value and make the body differ from its signed bytes
 * (LLD, <em>In-Process Component Host</em>, stage 0). Every contract record freezes through here instead.
 *
 * <p>Rule: maps, lists and sets are copied recursively into unmodifiable collections that keep their iteration order
 * and may hold {@code null} elements, as decoded JSON can. Every other value is kept as is and must itself be immutable;
 * a contract decoded by {@link ContractCodec} holds only strings, numbers and booleans there. The copy shares nothing
 * mutable with its source, so later changes to the source never show through.
 */
final class DeepImmutable {
    /** Utility class: static rule only. */
    private DeepImmutable() { }

    /**
     * Freezes a top-level extension map. Keeps the contract records' existing top-level semantics: {@code null} becomes
     * an empty map, and a {@code null} key or value is rejected with {@link NullPointerException}.
     *
     * @param source the map as supplied or decoded, possibly null
     * @return an immutable map whose nested maps, lists and sets are immutable too
     */
    static Map<String, Object> map(Map<String, ?> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> frozen = new LinkedHashMap<>();
        source.forEach((key, value) -> frozen.put(key, value(value)));
        return Map.copyOf(frozen);
    }

    /** Freezes one nested value: recurses into maps and collections, returns anything else unchanged. */
    private static Object value(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, value(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            for (Object item : set) copy.add(value(item));
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            for (Object item : collection) copy.add(value(item));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
