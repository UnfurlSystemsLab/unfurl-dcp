package com.unfurl.dcp.claim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The typed integration ports (DCP HLD-C2 A.9): read from the snake_case, upper-case-enum wire next to the opaque
 * {@code ports} wiring; absent ports stay absent and leave the claim hash unchanged; the validator enforces the A.9
 * rules.
 */
class IntegrationPortsTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ClaimValidator validator = new ClaimValidator();

    /** A claim with typed ports, as a product's catalog manifest declares them, reads into the typed records. */
    @Test
    void readsTypedPortsBesideTheWiringMap() {
        Claim claim = PackagedClaim.parse(manifestWith("""
                    ports:
                      agentRuntime: com.example.AgentRuntime
                    monitoring:
                      required: true
                      health_endpoints: [/health/live, /health/ready]
                      readiness_required: true
                      liveness_required: true
                    telemetry:
                      required: false
                      signals: [LOGS, AUDIT_EVENTS]
                      correlation_id_required: false
                    authentication:
                      required: false
                      accepted_modes: [JWT]
                    authorization:
                      required: false
                      decision_owner: SELF
                      permission_prefixes: [flow.]
                    ai:
                      required: true
                      intents: [summarize]
                      model_access: EXTERNAL_PROVIDER
                      cost_metered: true
                """));

        IntegrationPorts ports = claim.integrationPorts();
        assertThat(ports.ports()).containsEntry("agentRuntime", "com.example.AgentRuntime");
        assertThat(ports.monitoring()).isEqualTo(new MonitoringPort(true, List.of("/health/live", "/health/ready"), true, true, null));
        assertThat(ports.telemetry().signals()).containsExactly(TelemetrySignal.LOGS, TelemetrySignal.AUDIT_EVENTS);
        assertThat(ports.authentication().acceptedModes()).containsExactly(AuthenticationMode.JWT);
        assertThat(ports.authorization().decisionOwner()).isEqualTo(DecisionOwner.SELF);
        assertThat(ports.ai().modelAccess()).isEqualTo(ModelAccess.EXTERNAL_PROVIDER);
        assertThat(validator.validate(claim).valid()).isTrue();
    }

    /** A claim that declares only its wiring has no typed port at all: absent is never read as a default. */
    @Test
    void absentPortsAreNotDeclared() {
        Claim claim = PackagedClaim.parse(manifestWith("""
                    ports:
                      agentRuntime: com.example.AgentRuntime
                """));

        IntegrationPorts ports = claim.integrationPorts();
        assertThat(ports.monitoring()).isNull();
        assertThat(ports.telemetry()).isNull();
        assertThat(ports.authentication()).isNull();
        assertThat(ports.authorization()).isNull();
        assertThat(ports.ai()).isNull();
    }

    /** Typing the ports leaves a wiring-only claim's canonical bytes, and so its pinned hash, unchanged. */
    @Test
    void wiringOnlyClaimsKeepTheirHash() {
        Claim fixture = Fixtures.validProviderClaim();
        IntegrationPorts wiringOnly = new IntegrationPorts(Map.of("agentRuntime", "com.example.AgentRuntime"));

        assertThat(wiringOnly).isEqualTo(new IntegrationPorts(Map.of("agentRuntime", "com.example.AgentRuntime"),
                null, null, null, null, null));
        assertThat(ClaimHash.canonical(withPorts(fixture, wiringOnly)))
                .isEqualTo(ClaimHash.canonical(withPorts(fixture, new IntegrationPorts(Map.of("agentRuntime",
                        "com.example.AgentRuntime"), null, null, null, null, null))));
        assertThat(ClaimHash.canonical(withPorts(fixture, new IntegrationPorts(Map.of("agentRuntime", "com.example.AgentRuntime"),
                null, null, null, new MonitoringPort(true, List.of("/health"), false, false, null), null))))
                .as("declaring a port is a claim change")
                .isNotEqualTo(ClaimHash.canonical(withPorts(fixture, wiringOnly)));
    }

    /**
     * A declared port is complete: each port written as {@code {}} is refused, naming every required member, and
     * nothing is defaulted. Optional members stay absent, and an empty list is a value, not an absence.
     */
    @Test
    void refusesADeclaredPortWithMissingRequiredMembers() {
        Claim empty = unvalidated(manifestWith("""
                    authentication: {}
                    authorization: {}
                    telemetry: {}
                    monitoring: {}
                    ai: {}
                """));

        assertThat(empty.integrationPorts().monitoring().required()).isNull();
        assertThat(empty.integrationPorts().monitoring().healthEndpoints()).isNull();
        assertThat(validator.validate(empty).diagnostics())
                .filteredOn(diagnostic -> diagnostic.code() == ErrorCode.CLAIM_INTEGRATION_PORT_INVALID)
                .extracting(Diagnostic::fieldPath)
                .containsExactlyInAnyOrder(
                        "integration_ports.authentication.required", "integration_ports.authentication.accepted_modes",
                        "integration_ports.authorization.required", "integration_ports.authorization.decision_owner",
                        "integration_ports.telemetry.required", "integration_ports.telemetry.signals",
                        "integration_ports.telemetry.correlation_id_required",
                        "integration_ports.monitoring.required", "integration_ports.monitoring.readiness_required",
                        "integration_ports.monitoring.liveness_required",
                        "integration_ports.ai.required");
        assertThatThrownBy(() -> PackagedClaim.parse(manifestWith("""
                    monitoring: {}
                """))).isInstanceOf(PackagedClaim.PackagedClaimException.class);

        Claim minimal = PackagedClaim.parse(manifestWith("""
                    authentication: { required: false, accepted_modes: [] }
                    monitoring: { required: false, readiness_required: false, liveness_required: false }
                """));
        assertThat(minimal.integrationPorts().authentication().acceptedModes()).isEmpty();
        assertThat(minimal.integrationPorts().monitoring().healthEndpoints()).as("optional and not written").isNull();
    }

    /** An unknown enum value is refused when the claim is read, never mapped to something else. */
    @Test
    void refusesAnUnknownEnumValue() {
        assertThatThrownBy(() -> PackagedClaim.parse(manifestWith("""
                    telemetry:
                      required: true
                      signals: [LOGS, SCREENSHOTS]
                      correlation_id_required: false
                """))).isInstanceOf(PackagedClaim.PackagedClaimException.class);
    }

    /** A health endpoint must be an absolute path, and no list repeats an entry. */
    @Test
    void validatesTheA9Rules() {
        Claim fixture = Fixtures.validProviderClaim();
        for (String endpoint : List.of("health", "http://host/health", "//host/health", "/health?x=1", " ")) {
            Claim claim = withPorts(fixture, new IntegrationPorts(Map.of(), null, null, null,
                    new MonitoringPort(true, List.of(endpoint), false, false, null), null));
            assertThat(validator.validate(claim).diagnostics())
                    .as(endpoint)
                    .extracting(Diagnostic::code)
                    .contains(ErrorCode.CLAIM_INTEGRATION_PORT_INVALID);
        }
        Claim repeated = withPorts(fixture, new IntegrationPorts(Map.of(), null, null,
                new TelemetryPort(true, List.of(TelemetrySignal.LOGS, TelemetrySignal.LOGS), false, null), null, null));
        assertThat(validator.validate(repeated).diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo(ErrorCode.CLAIM_INTEGRATION_PORT_INVALID);
                    assertThat(diagnostic.fieldPath()).isEqualTo("integration_ports.telemetry.signals");
                });
    }

    /**
     * The shared fixture claim as a catalog manifest on the snake_case wire, with its {@code integration_ports} block
     * replaced by the given YAML, exactly as a product's {@code META-INF/unfurl-catalog.yaml} would carry it.
     */
    private static byte[] manifestWith(String integrationPorts) {
        try {
            ObjectNode root = YAML.valueToTree(Map.of("claim", Fixtures.validProviderClaim()));
            ((ObjectNode) root.path("claim")).set("integration_ports", YAML.readTree(integrationPorts.stripIndent()));
            root.set("catalog", YAML.valueToTree(Map.of("lifecycle", Map.of("status", "ACTIVE"))));
            return YAML.writeValueAsBytes(root);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The manifest's claim bound on the snake_case wire without validation, to look at what an invalid claim holds. */
    private static Claim unvalidated(byte[] manifest) {
        try {
            return YAML.treeToValue(YAML.readTree(manifest).path("claim"), Claim.class);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Claim withPorts(Claim claim, IntegrationPorts ports) {
        return new Claim(claim.identity(), claim.domain(), claim.refusals(), claim.dependencies(), claim.offers(),
                claim.conflictResolution(), claim.negotiationSurface(), ports, claim.faults(), claim.metadata(),
                claim.apiDocuments());
    }
}
