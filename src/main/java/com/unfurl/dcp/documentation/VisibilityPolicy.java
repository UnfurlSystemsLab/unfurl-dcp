package com.unfurl.dcp.documentation;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Value Object (product policy input, DCP HLD-C2 G.1 input 7): the visibility of each capability and API document.
 * Explicit entries win, and {@code defaultVisibility} covers everything else. DCP records the policy; the product decides
 * its values. There is no implicit default: the default itself is a required value.
 *
 * @param defaultVisibility visibility of anything not named explicitly
 * @param capabilities      visibility by capability name
 * @param apiDocuments      visibility by {@code api_id}
 */
public record VisibilityPolicy(Visibility defaultVisibility, Map<String, Visibility> capabilities,
                               Map<String, Visibility> apiDocuments) {
    /** Validating constructor: the default is required; maps are sorted copies. */
    public VisibilityPolicy {
        Objects.requireNonNull(defaultVisibility, "defaultVisibility");
        capabilities = Collections.unmodifiableMap(new TreeMap<>(capabilities == null ? Map.of() : capabilities));
        apiDocuments = Collections.unmodifiableMap(new TreeMap<>(apiDocuments == null ? Map.of() : apiDocuments));
    }

    /** Factory: one visibility for everything. */
    public static VisibilityPolicy uniform(Visibility visibility) {
        return new VisibilityPolicy(visibility, Map.of(), Map.of());
    }

    /** The visibility of a capability. */
    public Visibility ofCapability(String capability) {
        return capabilities.getOrDefault(capability, defaultVisibility);
    }

    /** The visibility of an API document. */
    public Visibility ofApiDocument(String apiId) {
        return apiDocuments.getOrDefault(apiId, defaultVisibility);
    }
}
