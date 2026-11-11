package com.unfurl.dcp.componentconfig;

import com.unfurl.dcp.componentconfig.ConfigKey.OverridePolicy;
import com.unfurl.dcp.componentconfig.ConfigKey.Source;
import com.unfurl.dcp.componentconfig.ConfigKey.Stricter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the strict Factory for {@code META-INF/unfurl-config.yaml} (LLD, <em>Component configuration</em>): the worked
 * example parses into typed keys and defaults, and every malformed schema is refused with all its problems listed.
 */
class ComponentConfigurationSchemaTest {
    /** The LLD's worked example (a broker read adapter), with concrete hosts. */
    static final String EXAMPLE = """
            config_version: 1
            schema:
              portfolioBase: { type: https-uri, source: packaged }
              allowedHosts:  { type: list<host>, source: packaged, override: tighten, max_items: 8 }
              currency:      { type: iso-currency, source: packaged }
              timeout:       { type: duration, min: PT1S, max: PT60S, source: packaged, override: tighten, stricter: lower }
              maxRows:       { type: integer, min: 1, max: 100000, source: packaged, override: tighten, stricter: lower }
              pageSize:      { type: integer, min: 1, max: 500, source: packaged, override: replace }
              strict:        { type: boolean, source: packaged }
              accountRef:    { type: string, max_length: 16, source: deployment }
              sessionRef:    { type: secret-ref, source: deployment }
            defaults:
              portfolioBase: https://portfolio.broker.example/api
              allowedHosts:  [portfolio.broker.example, trading.broker.example]
              currency:      INR
              timeout:       PT30S
              maxRows:       10000
              pageSize:      100
              strict:        true
            """;

    static ComponentConfigurationSchema example() {
        return ComponentConfigurationSchema.parse(EXAMPLE.getBytes(StandardCharsets.UTF_8));
    }

    /** The example yields sorted keys with their attributes and typed, immutable defaults. */
    @Test
    void parsesTheWorkedExample() {
        ComponentConfigurationSchema schema = example();

        assertThat(schema.keys().keySet()).containsExactly("accountRef", "allowedHosts", "currency", "maxRows",
                "pageSize", "portfolioBase", "sessionRef", "strict", "timeout");
        ConfigKey maxRows = schema.keys().get("maxRows");
        assertThat(maxRows.type()).isEqualTo(ConfigValueType.INTEGER);
        assertThat(maxRows.source()).isEqualTo(Source.PACKAGED);
        assertThat(maxRows.override()).isEqualTo(OverridePolicy.TIGHTEN);
        assertThat(maxRows.stricter()).isEqualTo(Stricter.LOWER);
        assertThat(maxRows.min()).isEqualTo(1L);
        assertThat(maxRows.max()).isEqualTo(100000L);
        assertThat(schema.keys().get("allowedHosts").describe()).isEqualTo("list<host>");
        assertThat(schema.keys().get("portfolioBase").override()).isEqualTo(OverridePolicy.FIXED);
        assertThat(schema.keys().get("sessionRef").secret()).isTrue();
        assertThat(schema.keys().get("accountRef").source()).isEqualTo(Source.DEPLOYMENT);

        assertThat(schema.defaults()).containsOnlyKeys("allowedHosts", "currency", "maxRows", "pageSize",
                "portfolioBase", "strict", "timeout");
        assertThat(schema.defaults().get("portfolioBase")).isEqualTo(URI.create("https://portfolio.broker.example/api"));
        assertThat(schema.defaults().get("timeout")).isEqualTo(Duration.ofSeconds(30));
        assertThat(schema.defaults().get("maxRows")).isEqualTo(10000L);
        assertThat(schema.defaults().get("allowedHosts")).isEqualTo(List.of("portfolio.broker.example", "trading.broker.example"));
        assertThatThrownBy(() -> schema.defaults().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) schema.defaults().get("allowedHosts")).add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** Each malformed schema is refused as CONFIG_INVALID on the offending key, naming the rule. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "k: { type: money, source: packaged }                                         | k | type must be",
            "k: { type: list<list<host>>, source: packaged }                              | k | type must be",
            "k: { type: string }                                                          | k | source must be",
            "k: { type: string, source: deployment, secret: true }                        | k | unknown attribute secret",
            "k: { type: string, source: deployment, override: replace }                   | k | override applies only to source: packaged",
            "k: { type: string, source: packaged, override: widen }                       | k | override must be",
            "k: { type: string, source: packaged, override: tighten }                     | k | tighten applies only to",
            "k: { type: integer, source: packaged, override: tighten }                    | k | tighten requires stricter",
            "k: { type: integer, source: packaged, override: tighten, stricter: down }    | k | stricter must be",
            "k: { type: integer, source: packaged, stricter: lower }                      | k | stricter applies only with",
            "k: { type: list<host>, source: packaged, override: tighten, stricter: lower }| k | stricter does not apply to a list",
            "k: { type: string, source: deployment, min: 1 }                              | k | min applies only to",
            "k: { type: integer, source: deployment, min: 5, max: 1 }                     | k | min must not exceed max",
            "k: { type: integer, source: deployment, min: low }                           | k | min must be an integer",
            "k: { type: integer, source: deployment, max_length: 4 }                      | k | max_length applies only to string",
            "k: { type: string, source: deployment, max_items: 4 }                        | k | max_items applies only to a list",
            "k: { type: list<host>, source: deployment, max_items: 0 }                    | k | max_items must be a positive",
            "'bad key': { type: string, source: deployment }                              | bad key | key name must match",
            "k: just-a-string                                                             | k | must be a mapping of attributes"
    })
    void refusesMalformedKeys(String key, String failingKey, String rule) {
        String yaml = "config_version: 1\nschema:\n  " + key.trim() + "\n";
        assertThatThrownBy(() -> ComponentConfigurationSchema.parse(yaml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ComponentConfigurationException.class, e -> assertThat(e.failures())
                        .anySatisfy(failure -> {
                            assertThat(failure.code()).isEqualTo(ConfigFailureCode.CONFIG_INVALID);
                            assertThat(failure.key()).isEqualTo(failingKey);
                            assertThat(failure.detail()).contains(rule);
                        }));
    }

    /** Defaults must be exactly the packaged keys and valid; every problem in one file is reported together. */
    @Test
    void collectsEveryDefaultProblem() {
        String yaml = """
                config_version: 1
                schema:
                  fixed:   { type: integer, max: 10, source: packaged }
                  missing: { type: host, source: packaged }
                  given:   { type: string, source: deployment }
                  token:   { type: secret-ref, source: packaged }
                defaults:
                  fixed: 11
                  given: not-allowed
                  token: hunter2-inline-secret
                  ghost: 1
                """;
        assertThatThrownBy(() -> ComponentConfigurationSchema.parse(yaml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ComponentConfigurationException.class, e -> {
                    assertThat(e.failures()).extracting(ConfigFailure::key, ConfigFailure::code).containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("fixed", ConfigFailureCode.CONFIG_INVALID),
                            org.assertj.core.groups.Tuple.tuple("missing", ConfigFailureCode.CONFIG_INVALID),
                            org.assertj.core.groups.Tuple.tuple("given", ConfigFailureCode.CONFIG_INVALID),
                            org.assertj.core.groups.Tuple.tuple("token", ConfigFailureCode.CONFIG_SECRET_INLINE),
                            org.assertj.core.groups.Tuple.tuple("ghost", ConfigFailureCode.CONFIG_INVALID));
                    assertThat(e.getMessage()).doesNotContain("hunter2");
                });
    }

    /** The document itself: one mapping, version 1, known top-level keys, a non-empty schema, unique keys. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "''                                                                 | one YAML mapping",
            "- a                                                                | one YAML mapping",
            "config_version: 1\\nschema: {a: {type: string, source: deployment}}\\n---\\nx: 1 | one YAML mapping",
            "config_version: 1\\nschema: {a: {type: string, source: deployment}, a: {type: host, source: deployment}} | one YAML mapping",
            "config_version: 2\\nschema: {a: {type: string, source: deployment}} | config_version must be 1",
            "schema: {a: {type: string, source: deployment}}                    | config_version must be 1",
            "config_version: 1\\nschema: {}                                     | schema must be a non-empty",
            "config_version: 1\\nschema: {a: {type: string, source: deployment}}\\nextra: 1 | unknown top-level key extra",
            "config_version: 1\\nschema: {a: {type: string, source: deployment}}\\ndefaults: [] | defaults must be a mapping"
    })
    void refusesMalformedDocuments(String yaml, String rule) {
        byte[] bytes = yaml.replace("\\n", "\n").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ComponentConfigurationSchema.parse(bytes))
                .isInstanceOfSatisfying(ComponentConfigurationException.class, e -> assertThat(e.failures())
                        .anySatisfy(failure -> {
                            assertThat(failure.key()).isEqualTo(ConfigFailure.DOCUMENT);
                            assertThat(failure.detail()).contains(rule);
                        }));
    }

    /** Each scalar type accepts its own form and rejects near misses. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https-uri    | https://api.example/v1      | true",
            "https-uri    | http://api.example/v1       | false",
            "https-uri    | https://user@api.example    | false",
            "https-uri    | https://api.example/v1?x=1  | false",
            "https-uri    | https:///nohost             | false",
            "host         | api.example                 | true",
            "host         | API.example                 | false",
            "host         | api.example:443             | false",
            "host         | -api.example                | false",
            "iso-currency | INR                         | true",
            "iso-currency | inr                         | false",
            "iso-currency | ZZZ                         | false",
            "duration     | PT30S                       | true",
            "duration     | -PT1S                       | false",
            "duration     | 30s                         | false",
            "secret-ref   | secret://tenant/session     | true",
            "secret-ref   | secret://has a space        | false",
            "secret-ref   | hunter2                     | false",
            "string       | anything at all             | true"
    })
    void scalarTypesAcceptOnlyTheirForm(String type, String value, boolean accepted) {
        String yaml = "config_version: 1\nschema:\n  k: { type: " + type + ", source: packaged }\ndefaults:\n  k: \"" + value + "\"\n";
        if (accepted) {
            assertThat(ComponentConfigurationSchema.parse(yaml.getBytes(StandardCharsets.UTF_8)).defaults()).containsKey("k");
        } else {
            assertThatThrownBy(() -> ComponentConfigurationSchema.parse(yaml.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ComponentConfigurationException.class)
                    .hasMessageNotContaining(value);
        }
    }

    /** Integers must be integral numbers (not strings or fractions); booleans must be booleans; a bare scheme is no reference. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "integer | 42      | true",
            "integer | \"42\"  | false",
            "integer | 4.2     | false",
            "integer | 99999999999999999999 | false",
            "boolean | false   | true",
            "boolean | \"yes\" | false",
            "secret-ref | \"secret://\" | false"
    })
    void numbersAndBooleansAreNotCoerced(String type, String literal, boolean accepted) {
        String yaml = "config_version: 1\nschema:\n  k: { type: " + type + ", source: packaged }\ndefaults:\n  k: " + literal + "\n";
        if (accepted) {
            assertThat(ComponentConfigurationSchema.parse(yaml.getBytes(StandardCharsets.UTF_8)).defaults()).containsKey("k");
        } else {
            assertThatThrownBy(() -> ComponentConfigurationSchema.parse(yaml.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ComponentConfigurationException.class);
        }
    }
}
