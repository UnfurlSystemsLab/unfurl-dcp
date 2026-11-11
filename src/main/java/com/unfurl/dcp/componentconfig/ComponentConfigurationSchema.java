package com.unfurl.dcp.componentconfig;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.unfurl.dcp.componentconfig.ConfigKey.OverridePolicy;
import com.unfurl.dcp.componentconfig.ConfigKey.Source;
import com.unfurl.dcp.componentconfig.ConfigKey.Stricter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Value Object: a component's packaged configuration file, {@code META-INF/unfurl-config.yaml}, parsed and checked
 * (LLD, <em>Component configuration</em>). It holds the schema of every key and the typed defaults of the
 * {@code source: packaged} keys. Hosts merge it with a runtime binding's deployment values; composers read the same
 * file at catalog scan, so both apply one rule set ({@link ComponentConfigurationValidator}).
 *
 * <p>{@link #parse} is a strict Factory: unknown top-level keys or attributes, a bound on a type it does not apply to,
 * a missing or surplus default, and a default that breaks its own key are all refused, and every problem is collected
 * into one {@link ComponentConfigurationException}. The file is read as data; no component code is involved.
 */
public final class ComponentConfigurationSchema {
    /** Archive path of the packaged configuration file; always the base path, never under {@code META-INF/versions/}. */
    public static final String RESOURCE = "META-INF/unfurl-config.yaml";
    /** The only supported {@code config_version}. */
    public static final int VERSION = 1;

    private static final Pattern KEY_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Set<String> TOP_LEVEL = Set.of("config_version", "schema", "defaults");
    private static final Set<String> ATTRIBUTES =
            Set.of("type", "source", "override", "stricter", "min", "max", "max_length", "max_items");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final Map<String, ConfigKey> keys;
    private final Map<String, Object> defaults;

    private ComponentConfigurationSchema(Map<String, ConfigKey> keys, Map<String, Object> defaults) {
        this.keys = Collections.unmodifiableMap(keys);
        this.defaults = Collections.unmodifiableMap(defaults);
    }

    /** Returns every key's schema, sorted by key name. */
    public Map<String, ConfigKey> keys() {
        return keys;
    }

    /** Returns the effective (typed) default of every {@code source: packaged} key, sorted by key name. */
    public Map<String, Object> defaults() {
        return defaults;
    }

    /**
     * Factory: parses and checks the packaged file.
     *
     * @param yaml the bytes of {@code META-INF/unfurl-config.yaml}
     * @return the checked schema
     * @throws ComponentConfigurationException listing every problem found
     */
    public static ComponentConfigurationSchema parse(byte[] yaml) {
        Objects.requireNonNull(yaml, "yaml");
        List<ConfigFailure> failures = new ArrayList<>();
        Map<?, ?> root = document(yaml);
        for (Object key : new TreeSet<>(stringKeys(root))) {
            if (!TOP_LEVEL.contains(key)) invalid(failures, ConfigFailure.DOCUMENT, "unknown top-level key " + key);
        }
        if (!(root.get("config_version") instanceof Integer version) || version != VERSION) {
            invalid(failures, ConfigFailure.DOCUMENT, "config_version must be " + VERSION);
        }
        Map<String, ConfigKey> keys = new TreeMap<>();
        if (!(root.get("schema") instanceof Map<?, ?> schema) || schema.isEmpty()) {
            invalid(failures, ConfigFailure.DOCUMENT, "schema must be a non-empty mapping of keys");
        } else {
            for (String name : new TreeSet<>(stringKeys(schema))) {
                ConfigKey key = key(name, schema.get(name), failures);
                if (key != null) keys.put(name, key);
            }
        }
        Map<String, Object> defaults = defaults(root.get("defaults"), keys, stringKeysOrEmpty(root.get("schema")), failures);
        if (!failures.isEmpty()) throw new ComponentConfigurationException(failures);
        return new ComponentConfigurationSchema(keys, defaults);
    }

    /** Reads exactly one YAML mapping document with unique keys; anything else is a single CONFIG_INVALID failure. */
    private static Map<?, ?> document(byte[] yaml) {
        try (JsonParser parser = YAML.createParser(yaml)) {
            Object parsed = parser.nextToken() == null ? null : parser.readValueAs(Object.class);
            if (parsed instanceof Map<?, ?> map && parser.nextToken() == null) return map;
        } catch (IOException malformed) {
            // Reported below without the parser's message, which can quote file content.
        }
        throw new ComponentConfigurationException(List.of(new ConfigFailure(ConfigFailureCode.CONFIG_INVALID,
                ConfigFailure.DOCUMENT, "the packaged configuration must be one YAML mapping with unique keys")));
    }

    /** Parses one key's attributes; returns null, having recorded why, when any attribute is wrong. */
    private static ConfigKey key(String name, Object raw, List<ConfigFailure> failures) {
        int before = failures.size();
        if (!KEY_NAME.matcher(name).matches()) invalid(failures, name, "key name must match " + KEY_NAME.pattern());
        if (!(raw instanceof Map<?, ?> attributes)) {
            invalid(failures, name, "must be a mapping of attributes");
            return null;
        }
        for (String attribute : new TreeSet<>(stringKeys(attributes))) {
            if (!ATTRIBUTES.contains(attribute)) invalid(failures, name, "unknown attribute " + attribute);
        }

        boolean list = false;
        ConfigValueType type = null;
        if (attributes.get("type") instanceof String text) {
            list = text.startsWith("list<") && text.endsWith(">");
            type = ConfigValueType.ofWireName(list ? text.substring(5, text.length() - 1) : text).orElse(null);
        }
        if (type == null) invalid(failures, name, "type must be a known scalar type or list<T> of one");

        Source source = choice(attributes.get("source"), Map.of("packaged", Source.PACKAGED, "deployment", Source.DEPLOYMENT));
        if (source == null) invalid(failures, name, "source must be packaged or deployment");

        OverridePolicy override = OverridePolicy.FIXED;
        if (attributes.containsKey("override")) {
            override = choice(attributes.get("override"), Map.of("tighten", OverridePolicy.TIGHTEN, "replace", OverridePolicy.REPLACE));
            if (override == null) invalid(failures, name, "override must be tighten or replace");
            else if (source == Source.DEPLOYMENT) invalid(failures, name, "override applies only to source: packaged");
        }
        Stricter stricter = null;
        if (attributes.containsKey("stricter")) {
            stricter = choice(attributes.get("stricter"), Map.of("lower", Stricter.LOWER, "higher", Stricter.HIGHER));
            if (stricter == null) invalid(failures, name, "stricter must be lower or higher");
        }

        Comparable<?> min = null;
        Comparable<?> max = null;
        Integer maxLength = positive(attributes, "max_length", name, failures);
        Integer maxItems = positive(attributes, "max_items", name, failures);
        if (type != null) {
            boolean orderedScalar = !list && type.ordered();
            if (override == OverridePolicy.TIGHTEN && list && attributes.containsKey("stricter")) {
                invalid(failures, name, "stricter does not apply to a list; a tightened list is a subset");
            } else if (override == OverridePolicy.TIGHTEN && !list && !orderedScalar) {
                invalid(failures, name, "tighten applies only to integer, duration and list types");
            } else if (override == OverridePolicy.TIGHTEN && orderedScalar && !attributes.containsKey("stricter")) {
                invalid(failures, name, "tighten requires stricter: lower or higher");
            } else if (override != OverridePolicy.TIGHTEN && attributes.containsKey("stricter")) {
                invalid(failures, name, "stricter applies only with override: tighten");
            }
            min = bound(attributes, "min", type, name, failures);
            max = bound(attributes, "max", type, name, failures);
            if (min != null && max != null && ConfigKey.compare(min, max) > 0) {
                invalid(failures, name, "min must not exceed max");
            }
            if (maxLength != null && type != ConfigValueType.STRING) invalid(failures, name, "max_length applies only to string");
            if (maxItems != null && !list) invalid(failures, name, "max_items applies only to a list");
        }
        return failures.size() == before
                ? new ConfigKey(name, type, list, source, override, stricter, min, max, maxLength, maxItems) : null;
    }

    /**
     * Checks the {@code defaults} block: exactly the packaged keys, each valid for its key.
     *
     * @param declared every key name the schema block declares, valid or not, so a default for a key whose schema was
     *                 already refused is not reported a second time as unknown
     */
    private static Map<String, Object> defaults(Object raw, Map<String, ConfigKey> keys, Set<String> declared,
                                                List<ConfigFailure> failures) {
        Map<String, Object> defaults = new TreeMap<>();
        Map<?, ?> given = Map.of();
        if (raw instanceof Map<?, ?> map) given = map;
        else if (raw != null) invalid(failures, ConfigFailure.DOCUMENT, "defaults must be a mapping of keys");

        for (ConfigKey key : keys.values()) {
            boolean present = given.containsKey(key.name());
            if (key.source() == Source.DEPLOYMENT) {
                if (present) invalid(failures, key.name(), "source: deployment forbids a packaged default");
            } else if (!present) {
                invalid(failures, key.name(), "source: packaged requires a default");
            } else {
                try {
                    defaults.put(key.name(), key.normalize(given.get(key.name())));
                } catch (ValueRejection rejection) {
                    failures.add(new ConfigFailure(rejection.code(), key.name(),
                            "the packaged default (" + key.describe() + ") " + rejection.getMessage()));
                }
            }
        }
        for (String name : new TreeSet<>(stringKeys(given))) {
            if (!declared.contains(name)) invalid(failures, name, "a default is given for a key the schema does not declare");
        }
        return defaults;
    }

    /** Parses an optional {@code min} or {@code max}, which must be a value of the key's ordered type. */
    private static Comparable<?> bound(Map<?, ?> attributes, String attribute, ConfigValueType type, String name,
                                       List<ConfigFailure> failures) {
        if (!attributes.containsKey(attribute)) return null;
        if (!type.ordered()) {
            invalid(failures, name, attribute + " applies only to integer and duration");
            return null;
        }
        try {
            return (Comparable<?>) type.parse(attributes.get(attribute));
        } catch (ValueRejection rejection) {
            invalid(failures, name, attribute + " " + rejection.getMessage());
            return null;
        }
    }

    /** Parses an optional positive whole-number attribute such as {@code max_items}. */
    private static Integer positive(Map<?, ?> attributes, String attribute, String name, List<ConfigFailure> failures) {
        if (!attributes.containsKey(attribute)) return null;
        if (attributes.get(attribute) instanceof Integer value && value > 0) return value;
        invalid(failures, name, attribute + " must be a positive whole number");
        return null;
    }

    /** Maps a raw attribute to one of its allowed values; null when it is absent or not allowed. */
    private static <T> T choice(Object raw, Map<String, T> allowed) {
        return raw instanceof String text ? allowed.get(text) : null;
    }

    /** Returns a mapping's keys as strings (YAML keys are read as strings). */
    private static Set<String> stringKeys(Map<?, ?> map) {
        Set<String> names = new TreeSet<>();
        for (Object key : map.keySet()) names.add(String.valueOf(key));
        return names;
    }

    /** As {@link #stringKeys}, or empty when the value is not a mapping. */
    private static Set<String> stringKeysOrEmpty(Object raw) {
        return raw instanceof Map<?, ?> map ? stringKeys(map) : Set.of();
    }

    private static void invalid(List<ConfigFailure> failures, String key, String detail) {
        failures.add(new ConfigFailure(ConfigFailureCode.CONFIG_INVALID, key, detail));
    }
}
