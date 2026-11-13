package com.unfurl.dcp.runtimebinding;

import com.unfurl.dcp.value.DeepImmutable;
import java.util.Map;

/**
 * Value Object: a runtime binding's non-secret configuration (HLD-C2 §C {@code configuration}).
 *
 * <p>{@code values} holds plain non-secret settings; {@code configRefs} holds typed references to
 * environment-specific configuration, such as an IdP token endpoint or a verification key set, which
 * must never be inlined; {@code secretRefs} holds typed {@code secret://} references for a binding that
 * needs several secrets, such as an identity binding's per-service client credentials (HLD-C2
 * {@code configuration.secret_refs}). All maps are copied and never null.
 */
public record Configuration(Map<String, Object> values, Map<String, ConfigRef> configRefs, Map<String, SecretRef> secretRefs) {
    /** Canonical constructor: defensive, null-safe copies. */
    public Configuration {
        // Deep copy: nested maps and lists are frozen too, so the record cannot change after it is built.
        values = DeepImmutable.map(values);
        configRefs = configRefs == null ? Map.of() : Map.copyOf(configRefs);
        secretRefs = secretRefs == null ? Map.of() : Map.copyOf(secretRefs);
    }

    /** Convenience constructor: values and configuration references, with no secret references. */
    public Configuration(Map<String, Object> values, Map<String, ConfigRef> configRefs) {
        this(values, configRefs, Map.of());
    }

    /** Convenience constructor: plain values with no configuration references. */
    public Configuration(Map<String, Object> values) {
        this(values, Map.of());
    }
}
