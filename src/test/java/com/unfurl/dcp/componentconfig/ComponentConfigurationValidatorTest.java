package com.unfurl.dcp.componentconfig;

import com.unfurl.dcp.componentconfig.EffectiveConfiguration.Origin;
import com.unfurl.dcp.componentconfig.EffectiveConfiguration.Source;
import com.unfurl.dcp.runtimebinding.ConfigRef;
import com.unfurl.dcp.runtimebinding.Configuration;
import com.unfurl.substrate.policy.ExecutionContext;
import com.unfurl.substrate.ports.host.ConfigResolver;
import com.unfurl.substrate.ports.host.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Tests the merge of packaged defaults with deployment values (LLD, <em>Component configuration</em>, merge and
 * checks): one case per failure code, collection of all failures, typed results with origins, redaction, the composer
 * check that resolves nothing, and pass-through for a component without a schema.
 */
class ComponentConfigurationValidatorTest {
    private static final ExecutionContext TENANT =
            new ExecutionContext("demo", "startup", List.of(), List.of(), "c", "r", Map.of(), Map.of());
    private static final Map<String, Object> REQUIRED =
            Map.of("accountRef", "primary-equity", "sessionRef", "secret://demo/kotak/session");
    private final ComponentConfigurationSchema schema = ComponentConfigurationSchemaTest.example();

    /** Only the two deployment keys are supplied: everything else is the packaged default, typed, with its origin. */
    @Test
    void mergesPackagedDefaultsWithRequiredDeploymentValues() {
        EffectiveConfiguration effective = ComponentConfigurationValidator.merge(schema, new Configuration(REQUIRED), null, TENANT);

        assertThat(effective.managed()).isTrue();
        assertThat(effective.require("portfolioBase", URI.class)).isEqualTo(URI.create("https://portfolio.broker.example/api"));
        assertThat(effective.require("timeout", Duration.class)).isEqualTo(Duration.ofSeconds(30));
        assertThat(effective.require("maxRows", Long.class)).isEqualTo(10000L);
        assertThat(effective.require("strict", Boolean.class)).isTrue();
        assertThat(effective.requireList("allowedHosts", String.class)).containsExactly("portfolio.broker.example", "trading.broker.example");
        assertThat(effective.require("accountRef", String.class)).isEqualTo("primary-equity");
        assertThat(effective.origins().get("maxRows")).isEqualTo(new Origin(Source.PACKAGED, null, null));
        assertThat(effective.origins().get("accountRef").source()).isEqualTo(Source.INLINE);
        assertThat(effective.values().keySet()).containsExactlyElementsOf(schema.keys().keySet());
    }

    /** Tightening and replacing are accepted inline and by reference; the reference's revision is recorded. */
    @Test
    void acceptsTighteningInlineAndByReference() {
        Map<String, Object> inline = new HashMap<>(REQUIRED);
        inline.put("timeout", "PT10S");
        inline.put("allowedHosts", List.of("portfolio.broker.example"));
        inline.put("pageSize", 500);
        Configuration deployment = new Configuration(inline, Map.of("maxRows", new ConfigRef("config://demo/kotak/max-rows")));
        ConfigResolver resolver = fixed("config://demo/kotak/max-rows", "rev-7", Map.of("value", 2000));

        EffectiveConfiguration effective = ComponentConfigurationValidator.merge(schema, deployment, resolver, TENANT);

        assertThat(effective.require("maxRows", Long.class)).isEqualTo(2000L);
        assertThat(effective.origins().get("maxRows")).isEqualTo(new Origin(Source.REFERENCE, "config://demo/kotak/max-rows", "rev-7"));
        assertThat(effective.require("timeout", Duration.class)).isEqualTo(Duration.ofSeconds(10));
        assertThat(effective.requireList("allowedHosts", String.class)).containsExactly("portfolio.broker.example");
        assertThat(effective.require("pageSize", Long.class)).isEqualTo(500L);
    }

    /** One run reports every bad key, each under its own code, ordered by key, without repeating any value. */
    @Test
    void collectsEveryFailureWithItsCode() {
        Map<String, Object> inline = new HashMap<>();
        inline.put("sessionRef", "hunter2-inline-secret");          // CONFIG_SECRET_INLINE
        inline.put("portfolioBase", "https://evil.example");         // CONFIG_KEY_FIXED
        inline.put("allowedHosts", List.of("portfolio.broker.example", "evil.example"));   // CONFIG_OVERRIDE_WIDENS
        inline.put("timeout", "PT45S");                              // CONFIG_OVERRIDE_WIDENS (looser than PT30S)
        inline.put("pageSize", 501);                                 // CONFIG_INVALID (max 500)
        inline.put("maxRows", 5);                                    // with a reference too: CONFIG_SOURCE_AMBIGUOUS
        inline.put("surprise", true);                                // CONFIG_KEY_UNKNOWN
        Configuration deployment = new Configuration(inline, Map.of(
                "maxRows", new ConfigRef("config://demo/kotak/max-rows"),
                "strict", new ConfigRef("config://demo/kotak/strict")));                  // CONFIG_KEY_FIXED
        // accountRef is absent: CONFIG_REQUIRED_MISSING

        assertThatThrownBy(() -> ComponentConfigurationValidator.merge(schema, deployment, null, TENANT))
                .isInstanceOfSatisfying(ComponentConfigurationException.class, e -> {
                    assertThat(e.failures()).extracting(ConfigFailure::key, ConfigFailure::code).containsExactly(
                            tuple("accountRef", ConfigFailureCode.CONFIG_REQUIRED_MISSING),
                            tuple("allowedHosts", ConfigFailureCode.CONFIG_OVERRIDE_WIDENS),
                            tuple("maxRows", ConfigFailureCode.CONFIG_SOURCE_AMBIGUOUS),
                            tuple("pageSize", ConfigFailureCode.CONFIG_INVALID),
                            tuple("portfolioBase", ConfigFailureCode.CONFIG_KEY_FIXED),
                            tuple("sessionRef", ConfigFailureCode.CONFIG_SECRET_INLINE),
                            tuple("strict", ConfigFailureCode.CONFIG_KEY_FIXED),
                            tuple("surprise", ConfigFailureCode.CONFIG_KEY_UNKNOWN),
                            tuple("timeout", ConfigFailureCode.CONFIG_OVERRIDE_WIDENS));
                    assertThat(e.getMessage()).doesNotContain("hunter2", "evil.example", "501");
                });
    }

    /** A tightened value must also stay inside the key's own bounds, and must be of the key's type. */
    @Test
    void tightenedValuesStillObeyTypeAndBounds() {
        assertFailure(with("maxRows", 0), "maxRows", ConfigFailureCode.CONFIG_INVALID, "at least 1");
        assertFailure(with("maxRows", "2000"), "maxRows", ConfigFailureCode.CONFIG_INVALID, "must be an integer");
        assertFailure(with("maxRows", 10001), "maxRows", ConfigFailureCode.CONFIG_OVERRIDE_WIDENS, "looser");
        assertFailure(with("allowedHosts", "portfolio.broker.example"), "allowedHosts", ConfigFailureCode.CONFIG_INVALID, "must be a list");
        assertFailure(with("accountRef", "x".repeat(17)), "accountRef", ConfigFailureCode.CONFIG_INVALID, "at most 16 characters");
    }

    /** Every way a reference can fail to resolve is CONFIG_REF_UNRESOLVED; a resolver's exception is kept as suppressed. */
    @Test
    void unresolvedReferencesAreRefused() {
        String ref = "config://demo/kotak/max-rows";
        assertRefFailure(ref, null, "binds no ConfigResolver");
        assertRefFailure(ref, (r, c) -> Optional.empty(), "does not exist");
        assertRefFailure(ref, fixed(ref, "rev", Map.of("maxRows", 2000)), "exactly { value");
        assertRefFailure(ref, fixed(ref, "rev", Map.of("value", 2000, "extra", 1)), "exactly { value");
        assertRefFailure(ref, fixed("config://demo/other", "rev", Map.of("value", 2000)), "different reference");
        assertRefFailure("file:///etc/passwd", fixed(ref, "rev", Map.of("value", 2000)), "must be a config:// URI");

        IllegalArgumentException refusal = new IllegalArgumentException("names another tenant");
        Configuration deployment = new Configuration(REQUIRED, Map.of("maxRows", new ConfigRef(ref)));
        assertThatThrownBy(() -> ComponentConfigurationValidator.merge(schema, deployment, (r, c) -> { throw refusal; }, TENANT))
                .isInstanceOfSatisfying(ComponentConfigurationException.class, e -> {
                    assertThat(e.failures()).singleElement().satisfies(failure -> {
                        assertThat(failure.code()).isEqualTo(ConfigFailureCode.CONFIG_REF_UNRESOLVED);
                        assertThat(failure.detail()).contains("resolver refused").doesNotContain("another tenant");
                    });
                    assertThat(e.getSuppressed()).containsExactly(refusal);
                });
    }

    /** A resolved value is held to the same rules as an inline one: it can tighten but not widen. */
    @Test
    void resolvedValuesAreCheckedLikeInlineOnes() {
        String ref = "config://demo/kotak/max-rows";
        Configuration deployment = new Configuration(REQUIRED, Map.of("maxRows", new ConfigRef(ref)));
        assertThatThrownBy(() -> ComponentConfigurationValidator.merge(schema, deployment,
                fixed(ref, "rev", Map.of("value", 50000)), TENANT))
                .isInstanceOfSatisfying(ComponentConfigurationException.class, e -> assertThat(e.failures())
                        .extracting(ConfigFailure::code).containsExactly(ConfigFailureCode.CONFIG_OVERRIDE_WIDENS));

        List<String> lookups = new ArrayList<>();
        ConfigResolver recording = (reference, context) -> {
            lookups.add(reference + "@" + context.tenantId());
            return Optional.of(new ResolvedConfig(reference, "rev", Map.of("value", 100)));
        };
        assertThat(ComponentConfigurationValidator.merge(schema, deployment, recording, TENANT).require("maxRows", Long.class))
                .isEqualTo(100L);
        assertThat(lookups).containsExactly(ref + "@demo");
    }

    /** The composer check decides what it can without a resolver, and never calls one. */
    @Test
    void composerCheckResolvesNothing() {
        Configuration good = new Configuration(REQUIRED, Map.of("maxRows", new ConfigRef("config://demo/kotak/max-rows")));
        assertThat(ComponentConfigurationValidator.check(schema, good)).isEmpty();

        Configuration bad = new Configuration(Map.of("sessionRef", "secret://demo/s", "portfolioBase", "https://x.example"),
                Map.of("maxRows", new ConfigRef("https://not-a-config-ref")));
        assertThat(ComponentConfigurationValidator.check(schema, bad)).extracting(ConfigFailure::key, ConfigFailure::code)
                .containsExactly(
                        tuple("accountRef", ConfigFailureCode.CONFIG_REQUIRED_MISSING),
                        tuple("maxRows", ConfigFailureCode.CONFIG_REF_UNRESOLVED),
                        tuple("portfolioBase", ConfigFailureCode.CONFIG_KEY_FIXED));
        assertThat(ComponentConfigurationValidator.check(schema, null)).extracting(ConfigFailure::code)
                .containsExactly(ConfigFailureCode.CONFIG_REQUIRED_MISSING, ConfigFailureCode.CONFIG_REQUIRED_MISSING);
    }

    /** Reports hide secret-ref values, and the effective configuration cannot be modified. */
    @Test
    void effectiveConfigurationIsRedactedAndImmutable() {
        EffectiveConfiguration effective = ComponentConfigurationValidator.merge(schema, new Configuration(REQUIRED), null, TENANT);

        assertThat(effective.redacted()).containsEntry("sessionRef", EffectiveConfiguration.REDACTED)
                .containsEntry("accountRef", "primary-equity");
        assertThat(effective.toString()).doesNotContain("secret://demo/kotak/session").contains(EffectiveConfiguration.REDACTED);
        assertThat(effective.require("sessionRef", String.class)).isEqualTo("secret://demo/kotak/session");
        assertThatThrownBy(() -> effective.values().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> effective.requireList("allowedHosts", String.class).add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> effective.require("absent", String.class)).hasMessageContaining("absent");
        assertThatThrownBy(() -> effective.require("maxRows", String.class)).hasMessageContaining("not a String")
                .hasMessageNotContaining("10000");
        assertThatThrownBy(() -> effective.requireList("allowedHosts", Long.class)).hasMessageContaining("list of Long");
    }

    /** A component without a schema gets the binding's inline values unchanged, deeply copied, marked unmanaged. */
    @Test
    void unmanagedConfigurationPassesInlineValuesThrough() {
        List<Object> hosts = new ArrayList<>(List.of("a.example"));
        Map<String, Object> values = new HashMap<>(Map.of("endpoint", "https://a.example", "hosts", hosts));
        EffectiveConfiguration effective = EffectiveConfiguration.unmanaged(new Configuration(values));
        hosts.add("b.example");

        assertThat(effective.managed()).isFalse();
        assertThat(effective.values()).containsEntry("endpoint", "https://a.example").containsEntry("hosts", List.of("a.example"));
        assertThat(effective.origins().values()).allMatch(origin -> origin.source() == Source.INLINE);
        assertThat(EffectiveConfiguration.unmanaged(null).values()).isEmpty();
    }

    private Configuration with(String key, Object value) {
        Map<String, Object> inline = new HashMap<>(REQUIRED);
        inline.put(key, value);
        return new Configuration(inline);
    }

    private void assertFailure(Configuration deployment, String key, ConfigFailureCode code, String rule) {
        assertThatThrownBy(() -> ComponentConfigurationValidator.merge(schema, deployment, null, TENANT))
                .isInstanceOfSatisfying(ComponentConfigurationException.class, e -> assertThat(e.failures())
                        .singleElement().satisfies(failure -> {
                            assertThat(failure.key()).isEqualTo(key);
                            assertThat(failure.code()).isEqualTo(code);
                            assertThat(failure.detail()).contains(rule);
                        }));
    }

    private void assertRefFailure(String reference, ConfigResolver resolver, String rule) {
        Configuration deployment = new Configuration(REQUIRED, Map.of("maxRows", new ConfigRef(reference)));
        assertThatThrownBy(() -> ComponentConfigurationValidator.merge(schema, deployment, resolver, TENANT))
                .isInstanceOfSatisfying(ComponentConfigurationException.class, e -> assertThat(e.failures())
                        .singleElement().satisfies(failure -> {
                            assertThat(failure.code()).isEqualTo(ConfigFailureCode.CONFIG_REF_UNRESOLVED);
                            assertThat(failure.detail()).contains(rule);
                        }));
    }

    /** Resolver fixture: answers every lookup with one fixed document. */
    private static ConfigResolver fixed(String reference, String revision, Map<String, Object> values) {
        return (requested, context) -> Optional.of(new ResolvedConfig(reference, revision, values));
    }
}
