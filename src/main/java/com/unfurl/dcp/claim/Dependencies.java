package com.unfurl.dcp.claim;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Value Object: what a component depends on, as dependency URIs ({@code <capability>@<version>[?<qualifiers>]}),
 * split by whether the component can work without them (HLD-C v0.2 §3.4).
 *
 * <p>{@code needs} are required: a composition that selects the component and leaves one unsatisfied is invalid.
 * {@code optional} dependencies are used when the composition provides them and their absence is accepted; a provided
 * optional dependency is an ordinary dependency edge. {@link ClaimValidator} rejects a capability listed in both.
 *
 * <p>{@code optional} is left out of serialization when empty, so a claim without optional dependencies keeps the
 * canonical bytes, and therefore the claim hash, it had before the field existed. Both lists are copied and never null.
 *
 * @param needs    required dependency URIs.
 * @param optional optional dependency URIs.
 */
public record Dependencies(
        List<String> needs,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> optional
) {
    /** Canonical constructor: defensive, null-safe copies. */
    public Dependencies {
        needs = needs == null ? List.of() : List.copyOf(needs);
        optional = optional == null ? List.of() : List.copyOf(optional);
    }

    /** Convenience constructor: required dependencies only. */
    public Dependencies(List<String> needs) {
        this(needs, List.of());
    }

    /**
     * Parser: the capability name of a dependency URI, the text before any {@code @version} or {@code ?qualifiers}.
     *
     * @param dependency a dependency URI.
     * @return the trimmed capability name, or null when the URI is null or names no capability.
     */
    public static String capabilityOf(String dependency) {
        if (dependency == null) {
            return null;
        }
        String name = dependency;
        int query = name.indexOf('?');
        if (query >= 0) {
            name = name.substring(0, query);
        }
        int version = name.indexOf('@');
        if (version >= 0) {
            name = name.substring(0, version);
        }
        name = name.trim();
        return name.isEmpty() ? null : name;
    }
}
