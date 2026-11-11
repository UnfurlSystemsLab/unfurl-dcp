package com.unfurl.dcp.componentconfig;

import com.unfurl.dcp.runtimebinding.Configuration;
import com.unfurl.dcp.value.DeepImmutable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Value Object: the configuration a component runs with, fixed for one admission (LLD, <em>Component
 * configuration</em>). For a component that packages a schema it is the validated merge of packaged defaults and
 * deployment values, with typed values (JDK types only) and the origin of each. For a component without a schema it is
 * the runtime binding's inline values, passed through unchanged.
 *
 * <p>It is deeply immutable. A {@code secret-ref} key holds a {@code secret://} reference, never a secret; even so,
 * {@link #redacted()} and {@link #toString()} hide those values, and reports and audit use only the redacted form.
 */
public final class EffectiveConfiguration {
    /** Placeholder shown instead of a {@code secret-ref} value. */
    public static final String REDACTED = "<secret-ref>";

    /** Where an effective value came from. */
    public enum Source { PACKAGED, INLINE, REFERENCE }

    /**
     * Value Object: the origin of one effective value.
     *
     * @param source    packaged default, inline binding value, or resolved reference
     * @param configRef the {@code config://} reference, for {@link Source#REFERENCE} only
     * @param revision  the resolver's revision of the referenced document, for {@link Source#REFERENCE} only
     */
    public record Origin(Source source, String configRef, String revision) {
        static final Origin PACKAGED = new Origin(Source.PACKAGED, null, null);
        static final Origin INLINE = new Origin(Source.INLINE, null, null);

        /** Validating constructor: a reference origin names its reference and revision; others name neither. */
        public Origin {
            if (source == null) throw new IllegalArgumentException("origin source is required");
            boolean reference = source == Source.REFERENCE;
            if (reference != (configRef != null) || reference != (revision != null)) {
                throw new IllegalArgumentException("only a reference origin carries a reference and revision");
            }
        }

        /** Factory: a value resolved from {@code configRef} at {@code revision}. */
        static Origin reference(String configRef, String revision) {
            return new Origin(Source.REFERENCE, configRef, revision);
        }
    }

    private final boolean managed;
    private final Map<String, Object> values;
    private final Map<String, Origin> origins;
    private final Set<String> secretKeys;

    /** Package-private: built by {@link ComponentConfigurationValidator#merge} from already immutable values. */
    EffectiveConfiguration(boolean managed, Map<String, Object> values, Map<String, Origin> origins, Set<String> secretKeys) {
        this.managed = managed;
        this.values = Collections.unmodifiableMap(new TreeMap<>(values));
        this.origins = Collections.unmodifiableMap(new TreeMap<>(origins));
        this.secretKeys = Collections.unmodifiableSet(new TreeSet<>(secretKeys));
    }

    /**
     * Factory: the configuration of a component that packages no schema. The binding's inline values pass through
     * unchanged (deeply copied), each with origin {@link Source#INLINE}; its {@code config_refs} stay on the runtime
     * binding for the component to read, as before schemas existed.
     */
    public static EffectiveConfiguration unmanaged(Configuration deployment) {
        Map<String, Object> values = deployment == null ? Map.of() : DeepImmutable.map(deployment.values());
        Map<String, Origin> origins = new TreeMap<>();
        values.keySet().forEach(key -> origins.put(key, Origin.INLINE));
        return new EffectiveConfiguration(false, values, origins, Set.of());
    }

    /** Returns whether a packaged schema governed this configuration. */
    public boolean managed() {
        return managed;
    }

    /** Returns every effective value by key, sorted by key. */
    public Map<String, Object> values() {
        return values;
    }

    /** Returns each value's origin by key, sorted by key. */
    public Map<String, Origin> origins() {
        return origins;
    }

    /**
     * Typed read of one value.
     *
     * @throws IllegalArgumentException when the key is absent or its value is not a {@code type}; the message names the
     *                                  key and types, never the value
     */
    public <T> T require(String key, Class<T> type) {
        Object value = values.get(key);
        if (value == null) throw new IllegalArgumentException("configuration key is absent: " + key);
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("configuration key " + key + " is not a " + type.getSimpleName());
        }
        return type.cast(value);
    }

    /**
     * Typed read of one list value.
     *
     * @throws IllegalArgumentException when the key is absent, is not a list, or holds an element that is not an
     *                                  {@code elementType}
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> requireList(String key, Class<T> elementType) {
        List<?> list = require(key, List.class);
        for (Object item : list) {
            if (!elementType.isInstance(item)) {
                throw new IllegalArgumentException("configuration key " + key + " is not a list of " + elementType.getSimpleName());
            }
        }
        return (List<T>) list;
    }

    /** Returns the values with every {@code secret-ref} value replaced by {@link #REDACTED}; safe for reports and audit. */
    public Map<String, Object> redacted() {
        Map<String, Object> shown = new TreeMap<>(values);
        secretKeys.forEach(key -> shown.computeIfPresent(key, (name, value) -> REDACTED));
        return Collections.unmodifiableMap(shown);
    }

    /** Diagnostic form: the redacted values only. */
    @Override
    public String toString() {
        return "EffectiveConfiguration" + redacted();
    }
}
