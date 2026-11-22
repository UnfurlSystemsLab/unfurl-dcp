package com.unfurl.dcp.runtimebinding;

import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.dcp.validation.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the DCP HTTP endpoint-shape rule (HLD-C2 C, G.3): an HTTP-served provider's {@code base_url} is exactly
 * {@code <service_root>/dcp/<provider_capability>}; in-process and external providers are not checked; a
 * {@code base_url_ref} is left to the resolver, because the validator does no I/O.
 */
class RuntimeBindingEndpointShapeTest {
    private final RuntimeBindingValidator validator = new RuntimeBindingValidator();
    private final CompositionContract contract = Fixtures.validContract();
    private final String capability = contract.binding().providerCapability();

    /** The DCP shape passes for every HTTP-served deployment kind. */
    @ParameterizedTest
    @EnumSource(value = DeploymentKind.class, names = {"CONTAINER", "REMOTE_SERVICE", "SIDECAR"})
    void dcpShapedEndpointPasses(DeploymentKind kind) {
        RuntimeBinding binding = binding(kind, URI.create("http://provider:8080/dcp/" + capability), null);

        assertThat(validator.validate(binding, contract).diagnostics())
                .noneSatisfy(d -> assertThat(d.code()).isEqualTo(ErrorCode.RUNTIME_BINDING_ENDPOINT_SHAPE));
    }

    /** A service root without the capability path, or with another capability, is rejected. */
    @Test
    void otherShapesAreRejected() {
        for (String endpoint : new String[]{"http://provider:8080", "http://provider:8080/contracts/" + capability,
                "http://provider:8080/dcp/other.capability"}) {
            RuntimeBinding binding = binding(DeploymentKind.CONTAINER, URI.create(endpoint), null);

            assertThat(validator.validate(binding, contract).diagnostics())
                    .as(endpoint)
                    .anySatisfy(d -> {
                        assertThat(d.code()).isEqualTo(ErrorCode.RUNTIME_BINDING_ENDPOINT_SHAPE);
                        assertThat(d.fieldPath()).isEqualTo("provider_instance.base_url");
                    });
        }
    }

    /** In-process, external and webapp providers carry no DCP endpoint, so no shape is enforced. */
    @ParameterizedTest
    @EnumSource(value = DeploymentKind.class, names = {"IN_PROCESS", "EXTERNAL_SAAS", "WEBAPP"})
    void nonHttpKindsAreNotChecked(DeploymentKind kind) {
        RuntimeBinding binding = binding(kind, URI.create("https://idp.example.test/realms/acme"), null);

        assertThat(validator.validate(binding, contract).diagnostics())
                .noneSatisfy(d -> assertThat(d.code()).isEqualTo(ErrorCode.RUNTIME_BINDING_ENDPOINT_SHAPE));
    }

    /** A config reference is checked where it is resolved, not here. */
    @Test
    void baseUrlRefIsLeftToTheResolver() {
        RuntimeBinding binding = binding(DeploymentKind.CONTAINER, null, new ConfigRef("config://acme/provider/endpoint"));

        assertThat(validator.validate(binding, contract).diagnostics())
                .noneSatisfy(d -> assertThat(d.code()).isEqualTo(ErrorCode.RUNTIME_BINDING_ENDPOINT_SHAPE));
    }

    /** Helper: a binding for the fixture contract with the given provider endpoint. */
    private RuntimeBinding binding(DeploymentKind kind, URI baseUrl, ConfigRef baseUrlRef) {
        return new RuntimeBinding(URI.create("urn:binding"), contract.contractId(), contract.contractVersion(),
                new TargetEnvironment("test"),
                new ProviderInstance(contract.parties().provider().claimUri(), contract.parties().provider().claimVersion(),
                        "provider", kind, baseUrl, baseUrlRef, null),
                null,
                new RuntimePolicy(true, null, "test", true),
                new Configuration(Map.of()),
                new DeploymentControls(Map.of()),
                new Lifecycle(true),
                null);
    }
}
