package com.unfurl.dcp.runtimebinding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the {@code runtime_binding_set} codec: a set round-trips unchanged and deterministically, a host overlay
 * without a root reads, and anything that is not exactly one well-formed set is refused without echoing content.
 */
class RuntimeBindingCodecTest {

    /** A set written by the codec reads back equal, with untyped values keeping their types. */
    @Test
    void roundTripsASetDeterministically() {
        RuntimeBindingSet set = new RuntimeBindingSet(URI.create("urn:binding:root"), Instant.parse("2026-11-10T00:00:00Z"),
                "signed.yaml", List.of(binding("urn:binding:root", "urn:contract:root", Map.of()),
                binding("urn:binding:a", "urn:contract:a", Map.of("audience", "1234", "maxRows", 2000, "strict", true,
                        "hosts", List.of("a.example")))));

        byte[] yaml = RuntimeBindingCodec.write(set);
        RuntimeBindingSet read = RuntimeBindingCodec.read(yaml);

        assertThat(read).isEqualTo(set);
        assertThat(RuntimeBindingCodec.write(read)).isEqualTo(yaml);
        assertThat(new String(yaml, StandardCharsets.UTF_8)).startsWith("runtime_binding_set:").contains("root_binding_id: urn:binding:root");
        Map<String, Object> values = read.byContractId().get(URI.create("urn:contract:a")).configuration().values();
        assertThat(values).containsEntry("audience", "1234").containsEntry("maxRows", 2000).containsEntry("strict", true);
        assertThat(read.byContractId()).containsOnlyKeys(URI.create("urn:contract:root"), URI.create("urn:contract:a"));
    }

    /** A host overlay holds only its own bindings: no root, and the informational fields may be absent. */
    @Test
    void readsASubsetWithoutARoot() {
        String yaml = """
                runtime_binding_set:
                  runtime_bindings:
                    - binding_id: urn:binding:a
                      contract_id: urn:contract:a
                      contract_version: 1.0.0
                      target_environment: { name: test }
                      provider_instance: { component_uri: urn:provider, component_version: 1.0.0, instance_name: p, deployment_kind: IN_PROCESS }
                      consumer_instance: { component_uri: urn:consumer, component_version: 1.0.0, instance_name: c }
                      runtime_policy: { enabled: true, timeout_ms: 1000, telemetry_namespace: test, audit_enabled: true }
                      configuration:
                        values: { accountRef: primary }
                        config_refs: { maxRows: { uri: "config://demo/kotak/max-rows" } }
                        secret_refs: { "urn:unfurl:foundry": { uri: "secret://demo/identity/foundry-service" } }
                      lifecycle: { enabled: true }
                """;
        RuntimeBindingSet set = RuntimeBindingCodec.read(yaml.getBytes(StandardCharsets.UTF_8));

        assertThat(set.rootBindingId()).isNull();
        RuntimeBinding binding = set.byContractId().get(URI.create("urn:contract:a"));
        assertThat(binding.providerInstance().deploymentKind()).isEqualTo(DeploymentKind.IN_PROCESS);
        assertThat(binding.configuration().values()).containsEntry("accountRef", "primary");
        assertThat(binding.configuration().configRefs()).containsEntry("maxRows", new ConfigRef("config://demo/kotak/max-rows"));
        assertThat(binding.configuration().secretRefs())
                .containsEntry("urn:unfurl:foundry", new SecretRef("secret://demo/identity/foundry-service"));
        assertThat(new String(RuntimeBindingCodec.write(set), StandardCharsets.UTF_8)).contains("secret_refs");
    }

    /** Anything but exactly one well-formed set is refused, and the message never repeats document content. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "''                                                                  | exactly one runtime_binding_set",
            "runtime_bindings: []                                                | exactly one runtime_binding_set",
            "runtime_binding_set: {runtime_bindings: []}\\nextra: 1              | exactly one runtime_binding_set",
            "runtime_binding_set: {runtime_bindings: []}\\n---\\nruntime_binding_set: {} | exactly one runtime_binding_set",
            "runtime_binding_set: {runtime_bindings: [], surprise: SECRET-VALUE} | unknown field: surprise",
            "runtime_binding_set: {runtime_bindings: [{binding_id: 'urn:b', contract_id: 'urn:c', oops: SECRET-VALUE}]} | unknown field: oops",
            "runtime_binding_set: {runtime_bindings: [{binding_id: 'urn:b', contract_id: 'urn:c'}, {binding_id: 'urn:b', contract_id: 'urn:d'}]} | binding id is missing or repeated",
            "runtime_binding_set: {runtime_bindings: [{binding_id: 'urn:b', contract_id: 'urn:c'}, {binding_id: 'urn:e', contract_id: 'urn:c'}]} | contract id is missing or repeated",
            "runtime_binding_set: {runtime_bindings: [{contract_id: 'urn:c'}]}   | binding id is missing or repeated",
            "runtime_binding_set: {root_binding_id: 'urn:r', root_binding_id: 'urn:s', runtime_bindings: []} | malformed at line 1"
    })
    void refusesAnythingButOneWellFormedSet(String yaml, String rule) {
        assertThatThrownBy(() -> RuntimeBindingCodec.read(yaml.replace("\\n", "\n").getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(rule)
                .hasMessageNotContaining("SECRET-VALUE");
    }

    private static RuntimeBinding binding(String id, String contract, Map<String, Object> values) {
        return new RuntimeBinding(URI.create(id), URI.create(contract), "1.0.0", new TargetEnvironment("test"),
                new ProviderInstance(URI.create("urn:provider"), "1.0.0", "provider", DeploymentKind.IN_PROCESS, null, null, null),
                new ConsumerInstance(URI.create("urn:consumer"), "1.0.0", "consumer"), new RuntimePolicy(true, 1000, "test", true),
                new Configuration(values), new DeploymentControls(Map.of()), new Lifecycle(true), null);
    }
}
