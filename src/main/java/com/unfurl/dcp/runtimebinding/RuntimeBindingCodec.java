package com.unfurl.dcp.runtimebinding;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Codec: reads and writes the {@code runtime_binding_set} YAML document (snake_case), the one place its envelope is
 * defined so composers and hosts agree on it (LLD, <em>Binding trust boundary</em>).
 *
 * <p>The document is exactly {@code { runtime_binding_set: { root_binding_id, generated_at, source_signed_contract,
 * runtime_bindings: [...] } }}. Reading is strict: one YAML document with unique keys, no field the model does not
 * define, and (through {@link RuntimeBindingSet}) unique binding and contract ids. Writing sorts properties, omits
 * nulls and keeps number-like strings quoted, so untyped configuration values read back with the type they were
 * written with.
 *
 * <p>The codec checks shape only. It verifies no signature, and it does not validate a binding against its contract;
 * that is {@link RuntimeBindingValidator}. A host must accept these bytes only from its trusted provisioning path.
 */
public final class RuntimeBindingCodec {
    private static final String ENVELOPE = "runtime_binding_set";
    private static final ObjectMapper YAML = mapper();

    /** Utility class: static codec only. */
    private RuntimeBindingCodec() { }

    /**
     * Parses a binding-set document.
     *
     * @param yaml the document bytes
     * @return the binding set
     * @throws IllegalArgumentException when the bytes are not exactly one well-formed binding-set document; the
     *                                  message names the problem (an unknown field by name, or the line), without repeating values
     */
    public static RuntimeBindingSet read(byte[] yaml) {
        Objects.requireNonNull(yaml, "yaml");
        try (JsonParser parser = YAML.createParser(yaml)) {
            JsonNode root = parser.nextToken() == null ? null : parser.readValueAsTree();
            if (root == null || !root.isObject() || root.size() != 1 || !root.has(ENVELOPE) || parser.nextToken() != null) {
                throw new IllegalArgumentException("a runtime binding document must be exactly one runtime_binding_set mapping");
            }
            RuntimeBindingSet set = YAML.treeToValue(root.get(ENVELOPE), RuntimeBindingSet.class);
            if (set == null) throw new IllegalArgumentException("runtime_binding_set is empty");
            return set;
        } catch (JsonProcessingException malformed) {
            // Jackson's message can quote document content, so only the location is kept. A model rule broken while
            // binding (for example a repeated binding id) arrives wrapped, and its own message is content-free.
            if (malformed.getCause() instanceof IllegalArgumentException rule) {
                throw new IllegalArgumentException(rule.getMessage(), malformed);
            }
            if (malformed instanceof UnrecognizedPropertyException unknown) {
                throw new IllegalArgumentException("the runtime binding document has an unknown field: " + unknown.getPropertyName(), malformed);
            }
            String where = malformed.getLocation() == null || malformed.getLocation().getLineNr() < 1
                    ? "" : " at line " + malformed.getLocation().getLineNr();
            throw new IllegalArgumentException("the runtime binding document is malformed" + where, malformed);
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("the runtime binding document cannot be read", unreadable);
        }
    }

    /**
     * Serializes a binding set as its YAML document.
     *
     * @param set the binding set
     * @return the document bytes, deterministic for equal sets
     */
    public static byte[] write(RuntimeBindingSet set) {
        Objects.requireNonNull(set, "set");
        try {
            return YAML.writeValueAsBytes(Map.of(ENVELOPE, set));
        } catch (IOException failure) {
            throw new IllegalStateException("unable to write runtime binding set", failure);
        }
    }

    /** Builder: the fixed mapper described in the class comment. */
    private static ObjectMapper mapper() {
        YAMLFactory factory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS);
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.registerModule(new JavaTimeModule());
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }
}
