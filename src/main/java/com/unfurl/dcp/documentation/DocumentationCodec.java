package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.util.Objects;

/**
 * Codec: the snake_case wire form of the projection model ({@code documentation-projection.schema.yaml}), the report view
 * of DCP HLD-C2 G.4, and the writer of rendered OpenAPI trees as JSON or YAML. Output is deterministic: properties
 * sorted, nulls omitted, ISO-8601 instants.
 */
public final class DocumentationCodec {
    private static final ObjectMapper JSON = configure(JsonMapper.builder().build());
    private static final ObjectMapper YAML = configure(new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)));
    private static final ObjectMapper PLAIN_JSON = new ObjectMapper();
    private static final ObjectMapper PLAIN_YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    private DocumentationCodec() { }

    /** The projection as JSON. */
    public static byte[] toJson(CapabilityDocumentation documentation) {
        return write(JSON, documentation);
    }

    /** The projection as YAML. */
    public static byte[] toYaml(CapabilityDocumentation documentation) {
        return write(YAML, documentation);
    }

    /** Parses a projection written by {@link #toJson}. */
    public static CapabilityDocumentation fromJson(byte[] json) {
        try {
            return JSON.readValue(json, CapabilityDocumentation.class);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("not a documentation projection", malformed);
        }
    }

    /** A rendered document (for example an OpenAPI tree) as JSON, keeping its own key order. */
    public static byte[] treeToJson(JsonNode tree) {
        return write(PLAIN_JSON, tree);
    }

    /** A rendered document as YAML, keeping its own key order. */
    public static byte[] treeToYaml(JsonNode tree) {
        return write(PLAIN_YAML, tree);
    }

    private static byte[] write(ObjectMapper mapper, Object value) {
        Objects.requireNonNull(value, "value");
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("unable to write documentation", failure);
        }
    }

    private static ObjectMapper configure(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }
}
