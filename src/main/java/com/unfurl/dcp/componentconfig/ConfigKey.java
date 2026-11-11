package com.unfurl.dcp.componentconfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Value Object: the schema of one configuration key from a packaged {@code META-INF/unfurl-config.yaml} (LLD,
 * <em>Component configuration</em>). It says what values the key accepts, who supplies it and how far a deployment may
 * change a packaged default. Built only by {@link ComponentConfigurationSchema#parse}, which has already checked that
 * the attributes are consistent (for example, that {@code stricter} is present exactly for a tightened ordered scalar).
 *
 * @param name      the key name
 * @param type      the scalar type; for a list, the type of each element
 * @param list      whether the value is a list of {@code type}
 * @param source    who supplies the value
 * @param override  how far a deployment may change a packaged default; {@link OverridePolicy#FIXED} for deployment keys
 * @param stricter  the stricter direction for a tightened ordered scalar; null otherwise
 * @param min       inclusive lower bound ({@link Long} or {@link java.time.Duration}); null when unbounded
 * @param max       inclusive upper bound; null when unbounded
 * @param maxLength maximum characters of a string (or of each string element); null when unbounded
 * @param maxItems  maximum elements of a list; null when unbounded
 */
public record ConfigKey(String name, ConfigValueType type, boolean list, Source source, OverridePolicy override,
                        Stricter stricter, Comparable<?> min, Comparable<?> max, Integer maxLength, Integer maxItems) {
    /** Who supplies a key's value. */
    public enum Source {
        /** The component: the packaged file carries a default. */
        PACKAGED,
        /** Each deployment: the runtime binding must supply it. */
        DEPLOYMENT
    }

    /** How far a deployment may change a packaged default. */
    public enum OverridePolicy {
        /** Not at all. */
        FIXED,
        /** Only toward stricter: lower or higher for an ordered scalar, a subset for a list. */
        TIGHTEN,
        /** To any valid value; the component author opted in explicitly. */
        REPLACE
    }

    /** The stricter direction of a tightened ordered scalar. */
    public enum Stricter { LOWER, HIGHER }

    /** Validating constructor: the identifying parts are required. */
    public ConfigKey {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("configuration key name is required");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(override, "override");
    }

    /** Returns whether the key holds {@code secret://} references, whose values reports must not show. */
    public boolean secret() {
        return type == ConfigValueType.SECRET_REF;
    }

    /** Returns the type as written in the packaged file, for example {@code list<host>}. */
    public String describe() {
        return list ? "list<" + type.wireName() + ">" : type.wireName();
    }

    /**
     * Converts a raw YAML value to this key's effective value, applying the type and every bound.
     *
     * @return the effective value: the scalar's Java type, or an immutable list of it
     * @throws ValueRejection naming the broken rule, never the value
     */
    Object normalize(Object raw) throws ValueRejection {
        if (!list) return scalar(raw);
        if (!(raw instanceof List<?> items)) throw new ValueRejection("must be a list");
        if (maxItems != null && items.size() > maxItems) {
            throw new ValueRejection("must have at most " + maxItems + " items");
        }
        List<Object> values = new ArrayList<>(items.size());
        for (Object item : items) values.add(scalar(item));
        return Collections.unmodifiableList(values);
    }

    /**
     * Tighten rule: whether an override is looser than the packaged default. A list widens when it holds an element
     * the default does not; an ordered scalar widens when it lies on the wrong side of the default.
     *
     * @param packagedDefault the effective packaged default
     * @param overriding      the effective override, already valid for this key
     */
    boolean widens(Object packagedDefault, Object overriding) {
        if (list) return !((List<?>) packagedDefault).containsAll((List<?>) overriding);
        int order = compare(overriding, packagedDefault);
        return stricter == Stricter.LOWER ? order > 0 : order < 0;
    }

    /** Applies the scalar type and its bounds to one value. */
    private Object scalar(Object raw) throws ValueRejection {
        Object value = type.parse(raw);
        if (min != null && compare(value, min) < 0) throw new ValueRejection("must be at least " + min);
        if (max != null && compare(value, max) > 0) throw new ValueRejection("must be at most " + max);
        if (maxLength != null && ((String) value).length() > maxLength) {
            throw new ValueRejection("must be at most " + maxLength + " characters");
        }
        return value;
    }

    /** Orders two effective values of this key's ordered type ({@link Long} or {@link java.time.Duration}). */
    @SuppressWarnings("unchecked")
    static int compare(Object left, Object right) {
        return ((Comparable<Object>) left).compareTo(right);
    }
}
