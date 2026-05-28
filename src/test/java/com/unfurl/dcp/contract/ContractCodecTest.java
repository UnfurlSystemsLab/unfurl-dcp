package com.unfurl.dcp.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.testing.Fixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContractCodecTest {
    @Test
    void usesPreferredSnakeCaseWireNames() throws Exception {
        String json = ContractCodec.canonicalMapper().writeValueAsString(Fixtures.validContract());

        assertThat(json).contains("contract_id");
        assertThat(json).contains("contract_version");
        assertThat(json).contains("data_mapping");
        assertThat(json).contains("created_by");
        assertThat(json).contains("human_in_loop");
        assertThat(json).doesNotContain("contractId");
    }

    @Test
    void jsonAndYamlInterconvertForCompositionContract() throws Exception {
        ObjectMapper json = ContractCodec.canonicalMapper();
        ObjectMapper yaml = yamlMapper();

        String yamlText = yaml.writeValueAsString(Fixtures.validContract());
        CompositionContract fromYaml = yaml.readValue(yamlText, CompositionContract.class);
        String jsonText = json.writeValueAsString(fromYaml);
        CompositionContract fromJson = json.readValue(jsonText, CompositionContract.class);

        assertThat(fromJson).isEqualTo(Fixtures.validContract());
    }

    private ObjectMapper yamlMapper() {
        return YAMLMapper.builder()
                .addModule(new JavaTimeModule())
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
    }
}
