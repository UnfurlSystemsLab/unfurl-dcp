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

    /**
     * Parser: the {@code owner=} qualifier of a dependency URI, which says who binds the dependency when it is not a
     * peer component in the composition (HLD-C v0.2 §3.4). The single definition shared by composers (which skip
     * externally owned needs when matching peers) and in-process hosts (which must cover {@code owner=host} needs).
     *
     * <p>The first {@code owner=} parameter in the {@code ?}-separated, {@code &}-delimited qualifiers wins; its value is
     * trimmed. Examples: {@code host.config@v1?owner=host} gives {@code host}; {@code agent.run@^0.1} gives the empty
     * string.
     *
     * @param dependency a dependency URI, possibly null
     * @return the owner, or the empty string when the URI is blank or carries no owner qualifier
     */
    public static String ownerOf(String dependency) {
        if (dependency == null || dependency.isBlank()) {
            return "";
        }
        int query = dependency.indexOf('?');
        if (query < 0 || query == dependency.length() - 1) {
            return "";
        }
        for (String part : dependency.substring(query + 1).split("&")) {
            if (part.startsWith("owner=")) {
                return part.substring("owner=".length()).trim();
            }
        }
        return "";
    }

    /**
     * Rule: whether a dependency is bound outside the peer-component graph, so a composer must not require a selected
     * component to offer it. Owners {@code host}, {@code fabric}, {@code customer-controlled} and any
     * {@code customer-*} owner are external; no owner, or any other owner, is a peer dependency.
     *
     * @param dependency a dependency URI, possibly null
     * @return true when {@link #ownerOf} names an external owner
     */
    public static boolean isExternallyOwned(String dependency) {
        String owner = ownerOf(dependency);
        return "host".equals(owner)
                || "fabric".equals(owner)
                || "customer-controlled".equals(owner)
                || owner.startsWith("customer-");
    }
}
