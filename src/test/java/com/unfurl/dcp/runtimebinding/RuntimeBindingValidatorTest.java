package com.unfurl.dcp.runtimebinding;

import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.dcp.validation.ErrorCode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeBindingValidatorTest {
    private final RuntimeBindingValidator validator = new RuntimeBindingValidator();

    @Test
    void rejectsInlineSecretsInFreeFormConfiguration() {
        RuntimeBinding binding = validBinding(new Configuration(Map.of("apiKey", "sk-1234567890123456")));

        assertThat(validator.validate(binding, Fixtures.validContract()).diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo(ErrorCode.BINDING_INLINE_SECRET));
    }

    @Test
    void rejectsRuntimePolicyFirewallOverrides() {
        RuntimeBinding binding = validBinding(new Configuration(Map.of("trustTier", "SELF")));

        assertThat(validator.validate(binding, Fixtures.validContract()).diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo(ErrorCode.BINDING_OVERRIDES_OWNERSHIP));
    }

    @Test
    void rejectsProviderAndConsumerVersionDriftFromContractParties() {
        RuntimeBinding binding = new RuntimeBinding(
                URI.create("urn:binding"),
                URI.create("urn:contract"),
                "1.0.0",
                new TargetEnvironment("test"),
                new ProviderInstance(URI.create("urn:provider"), "2.0.0", "provider", DeploymentKind.IN_PROCESS, null, null, null),
                new ConsumerInstance(URI.create("urn:consumer"), "1.0.0", "consumer"),
                new RuntimePolicy(true, 1000, "test", true),
                new Configuration(Map.of()),
                new DeploymentControls(Map.of()),
                new Lifecycle(true),
                null);

        assertThat(validator.validate(binding, Fixtures.validContract()).diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.fieldPath()).isEqualTo("provider_instance.component_version"));
    }

    @Test
    void rejectsRuntimeTimeoutsOutsideFrozenContractBounds() {
        RuntimeBinding binding = new RuntimeBinding(
                URI.create("urn:binding"),
                URI.create("urn:contract"),
                "1.0.0",
                new TargetEnvironment("test"),
                new ProviderInstance(URI.create("urn:provider"), "1.0.0", "provider", DeploymentKind.IN_PROCESS, null, null, null),
                new ConsumerInstance(URI.create("urn:consumer"), "1.0.0", "consumer"),
                new RuntimePolicy(true, 2000, "test", true),
                new Configuration(Map.of()),
                new DeploymentControls(Map.of()),
                new Lifecycle(true),
                null);

        assertThat(validator.validate(binding, Fixtures.validContract()).diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo(ErrorCode.BINDING_OVERRIDES_OWNERSHIP);
                    assertThat(diagnostic.fieldPath()).isEqualTo("runtime_policy.timeout_ms");
                });
    }

    @Test
    void validatesAggregateBindingTreeThroughContainsRefs() {
        RuntimeBinding child = binding("urn:child", new Configuration(Map.of()), null);
        RuntimeBinding parent = binding("urn:parent", new Configuration(Map.of()),
                new RuntimeBindingMetadata(Map.of("contains", java.util.List.of("urn:child"))));

        assertThat(validator.validateTree(parent,
                        Map.of(parent.bindingId(), parent, child.bindingId(), child),
                        Map.of(Fixtures.validContract().contractId(), Fixtures.validContract()))
                .valid()).isTrue();
    }

    @Test
    void rejectsMissingChildBindingRefs() {
        RuntimeBinding parent = binding("urn:parent", new Configuration(Map.of()),
                new RuntimeBindingMetadata(Map.of("contains", java.util.List.of("urn:missing"))));

        assertThat(validator.validateTree(parent,
                        Map.of(parent.bindingId(), parent),
                        Map.of(Fixtures.validContract().contractId(), Fixtures.validContract()))
                .diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo(ErrorCode.BINDING_CHILD_MISSING));
    }

    @Test
    void rejectsContainmentCycles() {
        RuntimeBinding parent = binding("urn:parent", new Configuration(Map.of()),
                new RuntimeBindingMetadata(Map.of("contains", java.util.List.of("urn:child"))));
        RuntimeBinding child = binding("urn:child", new Configuration(Map.of()),
                new RuntimeBindingMetadata(Map.of("contains", java.util.List.of("urn:parent"))));

        assertThat(validator.validateTree(parent,
                        Map.of(parent.bindingId(), parent, child.bindingId(), child),
                        Map.of(Fixtures.validContract().contractId(), Fixtures.validContract()))
                .diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo(ErrorCode.BINDING_CONTAINMENT_CYCLE));
    }

    @Test
    void rejectsInlineSecretsInChildBindings() {
        RuntimeBinding child = binding("urn:child", new Configuration(Map.of("apiKey", "sk-1234567890123456")), null);
        RuntimeBinding parent = binding("urn:parent", new Configuration(Map.of()),
                new RuntimeBindingMetadata(Map.of("contains", java.util.List.of("urn:child"))));

        assertThat(validator.validateTree(parent,
                        Map.of(parent.bindingId(), parent, child.bindingId(), child),
                        Map.of(Fixtures.validContract().contractId(), Fixtures.validContract()))
                .diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo(ErrorCode.BINDING_INLINE_SECRET));
    }

    private RuntimeBinding validBinding(Configuration configuration) {
        return binding("urn:binding", configuration, null);
    }

    private RuntimeBinding binding(String bindingId, Configuration configuration, RuntimeBindingMetadata metadata) {
        return new RuntimeBinding(
                URI.create(bindingId),
                URI.create("urn:contract"),
                "1.0.0",
                new TargetEnvironment("test"),
                new ProviderInstance(URI.create("urn:provider"), "1.0.0", "provider", DeploymentKind.IN_PROCESS, null, null, null),
                new ConsumerInstance(URI.create("urn:consumer"), "1.0.0", "consumer"),
                new RuntimePolicy(true, 1000, "test", true),
                configuration,
                new DeploymentControls(Map.of()),
                new Lifecycle(true),
                metadata);
    }
}
