package com.unfurl.dcp.serialization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.claim.ClaimMetadata;
import com.unfurl.dcp.contract.ContractCodec;
import com.unfurl.dcp.fault.*;
import com.unfurl.dcp.manifest.*;
import com.unfurl.dcp.questions.NegotiationQuestionSchema;
import com.unfurl.dcp.runtimebinding.*;
import com.unfurl.dcp.testing.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serialization contract test: public DCP records must round-trip through the
 * canonical JSON and YAML mappers using stable snake_case field names.
 */
class PublicRecordRoundTripTest {
    private static final ObjectMapper JSON = ContractCodec.canonicalMapper();
    private static final ObjectMapper YAML = yamlMapper();

    @ParameterizedTest(name = "{0}")
    @MethodSource("records")
    void publicRecordsRoundTripJsonAndYaml(String name, Object value, Class<?> type) throws Exception {
        Object fromJson = JSON.readValue(JSON.writeValueAsString(value), type);
        Object fromYaml = YAML.readValue(YAML.writeValueAsString(value), type);

        assertThat(fromJson).isEqualTo(value);
        assertThat(fromYaml).isEqualTo(value);
    }

    @Test
    void acceptsPreferredSnakeCaseWireNamesOnInput() throws Exception {
        String json = """
                {
                  "dcp_version": "0.2.0",
                  "claim_version": "1.0.0",
                  "created_at": "1970-01-01T00:00:00Z",
                  "extensions": {}
                }
                """;

        ClaimMetadata metadata = JSON.readValue(json, ClaimMetadata.class);

        assertThat(metadata.dcpVersion()).isEqualTo("0.2.0");
        assertThat(metadata.claimVersion()).isEqualTo("1.0.0");
        assertThat(metadata.createdAt()).isEqualTo(Instant.EPOCH);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> records() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("Claim", Fixtures.validProviderClaim(), com.unfurl.dcp.claim.Claim.class),
                org.junit.jupiter.params.provider.Arguments.of("FaultPolicy", Fixtures.providerFaultPolicy(), FaultPolicy.class),
                org.junit.jupiter.params.provider.Arguments.of("FaultSignal", faultSignal(), FaultSignal.class),
                org.junit.jupiter.params.provider.Arguments.of("CompositionContract", Fixtures.validContract(), com.unfurl.dcp.contract.CompositionContract.class),
                org.junit.jupiter.params.provider.Arguments.of("WebappManifest", manifest(), WebappManifest.class),
                org.junit.jupiter.params.provider.Arguments.of("RuntimeBinding", runtimeBinding(), RuntimeBinding.class),
                org.junit.jupiter.params.provider.Arguments.of("NegotiationQuestionSchema", NegotiationQuestionSchema.CANONICAL_V0_2, NegotiationQuestionSchema.class)
        );
    }

    private static WebappManifest manifest() {
        return new WebappManifest(
                URI.create("urn:provider"),
                "1.0.0",
                "/",
                List.of(new Route("/", "answers")),
                new Navigation(List.of("Home")),
                List.of("capability:answer.search", "concern:answers"),
                new ThemeContribution(ThemeMode.SUGGESTIVE, Map.of("accent", "#123456")),
                new Bootstrap(false, true));
    }

    /**
     * Fixture helper: builds a public runtime fault signal for codec
     * round-trips without depending on product adapters.
     */
    private static FaultSignal faultSignal() {
        return new FaultSignal(
                "fault-1",
                URI.create("urn:provider"),
                "provider-prod",
                URI.create("urn:contract"),
                URI.create("urn:binding"),
                "answer.search",
                "answer.search.timeout",
                FaultCategory.DEPENDENCY,
                FaultSeverity.DEGRADED,
                Instant.EPOCH,
                List.of("answer.search"),
                List.of("answer.search"),
                List.of(),
                List.of("trace://fault-1"),
                "corr-1");
    }

    private static RuntimeBinding runtimeBinding() {
        return new RuntimeBinding(
                URI.create("urn:binding"),
                URI.create("urn:contract"),
                "1.0.0",
                new TargetEnvironment("test"),
                new ProviderInstance(URI.create("urn:provider"), "1.0.0", "provider", DeploymentKind.IN_PROCESS, null, new ConfigRef("config://base-url"), new SecretRef("secret://provider")),
                new ConsumerInstance(URI.create("urn:consumer"), "1.0.0", "consumer"),
                new RuntimePolicy(true, 1000, "test", true),
                new Configuration(Map.of("setting", "value")),
                new DeploymentControls(Map.of("replicas", 1)),
                new Lifecycle(true),
                new RuntimeBindingMetadata(Map.of("contains", List.of("urn:binding:child"))));
    }

    private static ObjectMapper yamlMapper() {
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
