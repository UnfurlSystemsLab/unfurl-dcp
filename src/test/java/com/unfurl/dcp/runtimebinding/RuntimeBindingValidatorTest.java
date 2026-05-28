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
                new Lifecycle(true));

        assertThat(validator.validate(binding, Fixtures.validContract()).diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.fieldPath()).isEqualTo("provider_instance.component_version"));
    }

    private RuntimeBinding validBinding(Configuration configuration) {
        return new RuntimeBinding(
                URI.create("urn:binding"),
                URI.create("urn:contract"),
                "1.0.0",
                new TargetEnvironment("test"),
                new ProviderInstance(URI.create("urn:provider"), "1.0.0", "provider", DeploymentKind.IN_PROCESS, null, null, null),
                new ConsumerInstance(URI.create("urn:consumer"), "1.0.0", "consumer"),
                new RuntimePolicy(true, 1000, "test", true),
                configuration,
                new DeploymentControls(Map.of()),
                new Lifecycle(true));
    }
}
