package com.unfurl.dcp.runtimebinding;

import com.unfurl.dcp.contract.CompositionContract;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.unfurl.dcp.testing.DocumentationFixtures.contract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the shared identity binding reader (Fabric HLD-needs-driven-identity §5): absent exactly when no child provides
 * identity-context; when present it is complete, covers exactly the protected components, and is unique.
 */
class IdentityBindingTest {
    private static final URI IDENTITY = URI.create("urn:identity");
    private static final URI FLOW = URI.create("urn:unfurl:flow:runtime");
    private static final URI CATALOG = URI.create("urn:unfurl:api-catalog");

    /** Without an identity contract there is no identity binding, and that is not an error. */
    @Test
    void absentWithoutAnIdentityContract() {
        CompositionContract other = contract("urn:c:agent", "agent.run", "urn:unfurl:foundry", "0.1.0", FLOW.toString(), "0.1.0", Map.of());

        assertThat(IdentityBinding.from(List.of(other), Map.of())).isEmpty();
    }

    /** A complete binding is read with every value and reference. */
    @Test
    void completeBindingIsRead() {
        CompositionContract identity = identityContract(List.of(FLOW.toString(), CATALOG.toString()));

        IdentityBinding binding = IdentityBinding.from(List.of(identity), Map.of(IDENTITY, binding(audiences(FLOW, CATALOG), true))).orElseThrow();

        assertThat(binding.issuer()).isEqualTo("https://idp.example.test/realms/acme");
        assertThat(binding.protectedComponents()).containsExactly(CATALOG, FLOW);
        assertThat(binding.audience(CATALOG)).contains("api-catalog");
        assertThat(binding.tokenEndpointRef()).isEqualTo("config://acme/identity/token-endpoint");
        assertThat(binding.verificationKeysRef()).isEqualTo("config://acme/identity/keys");
        assertThat(binding.clientCredentialsRef()).isEqualTo("secret://acme/identity/host");
        assertThat(binding.serviceCredentials(CATALOG)).contains("secret://acme/identity/api-catalog");
    }

    /** Audiences must equal exactly the recorded protected components, in both directions. */
    @Test
    void audiencesMustCoverExactlyTheProtectedComponents() {
        CompositionContract identity = identityContract(List.of(FLOW.toString(), CATALOG.toString()));

        assertThatThrownBy(() -> IdentityBinding.from(List.of(identity), Map.of(IDENTITY, binding(audiences(FLOW), true))))
                .isInstanceOf(IdentityBinding.IdentityBindingException.class)
                .hasMessageContaining(IdentityBinding.INCOMPLETE).hasMessageContaining("no audience for " + CATALOG);
        assertThatThrownBy(() -> IdentityBinding.from(List.of(identityContract(List.of(FLOW.toString()))),
                Map.of(IDENTITY, binding(audiences(FLOW, CATALOG), true))))
                .hasMessageContaining(CATALOG + " does not need identity-context");
    }

    /** A missing neededBy record is refused, never read as "nothing protected". */
    @Test
    void missingNeededByIsRefused() {
        CompositionContract identity = contract(IDENTITY.toString(), "identity-context", "urn:keycloak", "1.0.0", "urn:host", "1.0.0", Map.of());

        assertThatThrownBy(() -> IdentityBinding.from(List.of(identity), Map.of(IDENTITY, binding(audiences(FLOW), true))))
                .hasMessageContaining("neededBy");
    }

    /** A missing binding, missing references or a second identity contract are refused. */
    @Test
    void incompleteOrDuplicateIdentityIsRefused() {
        CompositionContract identity = identityContract(List.of(FLOW.toString()));

        assertThatThrownBy(() -> IdentityBinding.from(List.of(identity), Map.of()))
                .hasMessageContaining("has no runtime binding");
        assertThatThrownBy(() -> IdentityBinding.from(List.of(identity), Map.of(IDENTITY, binding(audiences(FLOW), false))))
                .hasMessageContaining("verification_keys");
        CompositionContract second = contract("urn:identity-2", "identity-context", "urn:keycloak", "1.0.0", "urn:host", "1.0.0",
                Map.of("neededBy", List.of(FLOW.toString())));
        assertThatThrownBy(() -> IdentityBinding.from(List.of(identity, second), Map.of(IDENTITY, binding(audiences(FLOW), true))))
                .hasMessageContaining("at most one");
    }

    /** Helper: an identity contract recording the given protected components. */
    private static CompositionContract identityContract(List<String> neededBy) {
        return contract(IDENTITY.toString(), "identity-context", "urn:keycloak", "1.0.0", "urn:host", "1.0.0",
                Map.of("neededBy", neededBy));
    }

    /** Helper: an audiences value naming each component after its URI's last part. */
    private static Map<String, Object> audiences(URI... components) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (URI component : components) {
            String text = component.toString();
            result.put(text, text.substring(text.lastIndexOf(':') + 1));
        }
        return result;
    }

    /** Helper: an identity runtime binding; {@code withKeys} false leaves out the verification keys reference. */
    private static RuntimeBinding binding(Map<String, Object> audiences, boolean withKeys) {
        Map<String, ConfigRef> refs = new LinkedHashMap<>();
        refs.put("token_endpoint", new ConfigRef("config://acme/identity/token-endpoint"));
        if (withKeys) refs.put("verification_keys", new ConfigRef("config://acme/identity/keys"));
        Map<String, Object> values = Map.of(
                "issuer", "https://idp.example.test/realms/acme",
                "audiences", audiences,
                "claims", Map.of("tenant", "tenant", "roles", "roles", "permissions", "permissions"));
        return new RuntimeBinding(URI.create("urn:binding:identity"), IDENTITY, "1.0.0", new TargetEnvironment("test"),
                new ProviderInstance(URI.create("urn:keycloak"), "1.0.0", "idp", DeploymentKind.EXTERNAL_SAAS, null, null,
                        new SecretRef("secret://acme/identity/host")),
                null, new RuntimePolicy(true, null, "identity", true),
                new Configuration(values, refs, Map.of(CATALOG.toString(), new SecretRef("secret://acme/identity/api-catalog"))),
                new DeploymentControls(Map.of()), new Lifecycle(true), null);
    }
}
