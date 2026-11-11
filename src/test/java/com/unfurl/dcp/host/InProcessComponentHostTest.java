package com.unfurl.dcp.host;

import com.unfurl.dcp.broker.BrokerEvent;
import com.unfurl.dcp.broker.BrokerEventType;
import com.unfurl.dcp.broker.CompositionBroker;
import com.unfurl.dcp.broker.DefaultCompositionBroker;
import com.unfurl.dcp.broker.Disposition;
import com.unfurl.dcp.broker.RegistrationHandle;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimHash;
import com.unfurl.dcp.claim.ClaimValidator;
import com.unfurl.dcp.claim.Dependencies;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.Parties;
import com.unfurl.dcp.contract.Party;
import com.unfurl.dcp.contract.Transport;
import com.unfurl.dcp.contract.TransportKind;
import com.unfurl.dcp.runtimebinding.Configuration;
import com.unfurl.dcp.runtimebinding.ConsumerInstance;
import com.unfurl.dcp.runtimebinding.DeploymentControls;
import com.unfurl.dcp.runtimebinding.DeploymentKind;
import com.unfurl.dcp.runtimebinding.Lifecycle;
import com.unfurl.dcp.runtimebinding.ProviderInstance;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.runtimebinding.RuntimePolicy;
import com.unfurl.dcp.runtimebinding.TargetEnvironment;
import com.unfurl.dcp.spi.BrokerEventSink;
import com.unfurl.dcp.spi.CapabilityRegistrar;
import com.unfurl.dcp.spi.ContractInvocableFactory;
import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.dcp.trust.OfflineContractVerifier;
import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;
import com.unfurl.substrate.ports.host.OperationAuditSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the admitting host end to end (LLD, <em>In-Process Component Host</em>, admission flow and rollback) with a
 * synthetic component compiled and packed at test time: a successful admission that serves invocations, every stage-1
 * and stage-2 refusal, that no component code runs before stage 1 has passed, and that no failure leaves a
 * registration behind, with and without an exception-safe broker.
 */
class InProcessComponentHostTest {
    private static final Party HOST = new Party(URI.create("urn:consumer"), "1.0.0");
    private static final ExecutionContext STARTUP =
            new ExecutionContext("demo", "startup", List.of(), List.of(), "corr", "req", Map.of(), Map.of());
    private static final String CONFIG = """
            config_version: 1
            schema:
              maxRows:    { type: integer, min: 1, max: 1000, source: packaged, override: tighten, stricter: lower }
              accountRef: { type: string, source: deployment }
              sessionRef: { type: secret-ref, source: deployment }
            defaults:
              maxRows: 100
            """;
    private static final Map<String, Object> VALUES = Map.of("accountRef", "primary", "sessionRef", "secret://demo/session");

    private static Path scratch;
    private static Map<String, byte[]> classes;

    @TempDir Path dir;
    private final List<BrokerEvent> events = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void compileComponent() throws IOException {
        scratch = Files.createTempDirectory("dcp-hosted-fixtures");
        classes = ComponentJars.compile(scratch, Map.of("example.hosted.ToolActivation", """
                package example.hosted;
                import com.unfurl.dcp.contract.Party;
                import com.unfurl.dcp.host.ActivationContext;
                import com.unfurl.dcp.host.ComponentActivation;
                import com.unfurl.dcp.host.HostedSwitches;
                import com.unfurl.dcp.host.RecordingToolRegistrar;
                import com.unfurl.dcp.host.TestToolRegistrar;
                import com.unfurl.dcp.spi.CapabilityRegistrar;
                import com.unfurl.dcp.spi.ContractInvocableFactory;
                import com.unfurl.substrate.composition.ContractInvocable;
                import com.unfurl.substrate.composition.ContractInvocation;
                import com.unfurl.substrate.composition.ContractInvocationResult;
                import com.unfurl.substrate.policy.ExecutionContext;
                import com.unfurl.substrate.ports.host.OperationAuditSink;
                import java.net.URI;
                import java.util.Map;
                import java.util.Optional;
                import java.util.Set;
                public class ToolActivation implements ComponentActivation {
                    public ToolActivation() { HostedSwitches.constructed.incrementAndGet(); }
                    public URI claimUri() { return URI.create("urn:provider"); }
                    public String claimVersion() { return HostedSwitches.claimVersion; }
                    public Map<String, Set<Class<?>>> hostDependencies() { return HostedSwitches.hostDependencies; }
                    public ContractInvocableFactory factory(ActivationContext context) {
                        if (HostedSwitches.factoryThrows) throw new IllegalStateException("factory boom");
                        return (contract, binding, startup) -> new ContractInvocable() {
                            public String contractId() { return contract.contractId().toString(); }
                            public String contractVersion() { return contract.contractVersion(); }
                            public ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext caller) {
                                return ContractInvocationResult.success(Map.of(
                                        "capability", binding.providerCapability(),
                                        "maxRows", String.valueOf(context.configuration().values().get("maxRows")),
                                        "accountRef", String.valueOf(context.configuration().values().get("accountRef")),
                                        "scoped", Thread.currentThread().getContextClassLoader() == ToolActivation.class.getClassLoader(),
                                        "audit", context.host().port(OperationAuditSink.class).isPresent()));
                            }
                        };
                    }
                    public <R extends CapabilityRegistrar> Optional<R> registrar(Class<R> shape, Party consumer) {
                        if (!HostedSwitches.supportShape || shape != TestToolRegistrar.class) return Optional.empty();
                        return Optional.of(shape.cast(new RecordingToolRegistrar(consumer)));
                    }
                }
                """));
    }

    @AfterAll
    static void deleteFixtures() throws IOException {
        try (Stream<Path> files = Files.walk(scratch)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    @BeforeEach
    void resetSwitches() {
        HostedSwitches.reset();
    }

    /** A valid set is admitted through the broker, serves scoped invocations with the merged configuration, and closes cleanly. */
    @Test
    void admitsEveryOperationServesInvocationsAndRevokesOnClose() throws Exception {
        Setup setup = new Setup();
        setup.remoteContract = true;
        InProcessComponentHost host = setup.host();

        HostReport<TestToolRegistrar> report = host.admit(setup.bindings(), setup.inspect(), TestToolRegistrar.class, HOST, STARTUP);

        assertThat(HostedSwitches.constructed).hasValue(1);
        assertThat(report.registrars()).containsOnlyKeys("urn:provider@1.0.0");
        assertThat(report.operations()).extracting(HostReport.AdmittedOperation::capability).containsExactly("tool.a", "tool.b");
        assertThat(report.ignoredArtifacts()).containsExactly("stray.jar");
        assertThat(report.notHostedContracts()).containsExactly("urn:contract:remote@1.0.0");
        HostReport.AdmittedOperation first = report.operations().get(0);
        assertThat(first.configuration()).containsEntry("maxRows", 100L).containsEntry("accountRef", "primary")
                .containsEntry("sessionRef", "<secret-ref>");
        assertThat(first.configurationOrigins()).containsOnlyKeys("accountRef", "maxRows", "sessionRef");

        RecordingToolRegistrar registrar = (RecordingToolRegistrar) report.registrars().get("urn:provider@1.0.0");
        assertThat(registrar.consumer()).isEqualTo(HOST);
        assertThat(registrar.tools()).containsOnlyKeys("tool.a", "tool.b");
        ContractInvocationResult result = registrar.tools().get("tool.a").invoke(new ContractInvocation(
                "urn:contract:a", "tool.a", "urn:consumer", "urn:provider", Map.of(), "corr", Map.of(), null, Map.of()), STARTUP);
        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("capability", "tool.a").containsEntry("maxRows", "100")
                .containsEntry("accountRef", "primary").containsEntry("scoped", true).containsEntry("audit", true);
        assertThat(events).extracting(BrokerEvent::type).filteredOn(BrokerEventType.CAPABILITY_REGISTERED::equals).hasSize(2);
        assertThatThrownBy(() -> host.admit(setup.bindings(), setup.artifacts, TestToolRegistrar.class, HOST, STARTUP))
                .isInstanceOf(IllegalStateException.class);

        host.close();
        host.close();
        assertThat(registrar.tools()).isEmpty();
        assertThat(events).extracting(BrokerEvent::type).filteredOn(BrokerEventType.CAPABILITY_REVOKED::equals).hasSize(2);
    }

    /** A component that packages no configuration receives its binding's inline values unchanged. */
    @Test
    void passesInlineValuesThroughWhenTheComponentPackagesNoSchema() throws Exception {
        Setup setup = new Setup();
        setup.configYaml = null;
        try (InProcessComponentHost host = setup.host()) {
            HostReport<TestToolRegistrar> report = host.admit(setup.bindings(), setup.inspect(), TestToolRegistrar.class, HOST, STARTUP);
            assertThat(report.operations().get(0).configuration()).isEqualTo(VALUES);
        }
    }

    /** Stage 0: a contract that does not verify never reaches the host, and the component never runs. */
    @Test
    void anUnverifiedContractStopsBeforeAnyComponentCode() throws Exception {
        Setup setup = new Setup();
        byte[] envelope = HostFixtures.freeze(setup.contract("urn:contract:a", "tool.a")).canonicalBytes();
        String text = new String(envelope, StandardCharsets.UTF_8);
        int body = text.indexOf("\"contract\"");
        String tampered = text.substring(0, body) + text.substring(body).replaceFirst("tool\\.a", "tool.b");

        assertThatThrownBy(() -> VerifiedContracts.load(List.of(new ContractSource("a.json", tampered.getBytes(StandardCharsets.UTF_8))),
                HostFixtures.keys())).isInstanceOfSatisfying(ComponentHostException.class,
                e -> assertThat(e.code()).isEqualTo(HostFailureCode.CONTRACT_UNVERIFIED));
        assertThat(HostedSwitches.constructed).hasValue(0);
    }

    /** Every stage-1 check refuses with its own code, and none of them runs component code. */
    @Test
    void refusesInStageOneWithoutRunningComponentCode() throws Exception {
        Claim other = withIdentityUri(claim("tool.a", "tool.b"), "urn:someone-else");
        refused(HostFailureCode.PROVIDER_PIN_MISSING, setup -> setup.pinContracts = false);
        refused(HostFailureCode.COMPONENT_CLAIM_MISMATCH, setup -> setup.packagedClaim = other);
        refused(HostFailureCode.CLAIM_HASH_MISMATCH, setup -> setup.claimHash = "f".repeat(64));
        refused(HostFailureCode.CONSUMER_MISMATCH, setup -> setup.contractConsumer = new Party(URI.create("urn:unfurl:host"), "0.0.0"));
        refused(HostFailureCode.CONSUMER_MISMATCH, setup -> setup.bindingConsumer = new Party(URI.create("urn:unfurl:host"), "0.0.0"));
        refused(HostFailureCode.RUNTIME_BINDING_INVALID, setup -> setup.omitBindings = true);
        refused(HostFailureCode.RUNTIME_BINDING_INVALID, setup -> setup.bindingProviderVersion = "2.0.0");
        refused(HostFailureCode.RUNTIME_BINDING_DISABLED, setup -> setup.enabled = false);
        refused(HostFailureCode.CONFIG_REQUIRED_MISSING, setup -> setup.values = Map.of("sessionRef", "secret://demo/session"));
        refused(HostFailureCode.CONFIG_OVERRIDE_WIDENS, setup -> setup.values = with(VALUES, "maxRows", 500));
        refused(HostFailureCode.CONFIG_INVALID, setup -> setup.configYaml = "config_version: 7\n");
        refused(HostFailureCode.ADMISSION_REJECTED, setup -> setup.packagedClaim = claim("tool.a"));
        assertThat(HostedSwitches.constructed).as("no stage-1 refusal constructed the activation").hasValue(0);
    }

    /** Several configuration problems are reported together under the first one's code. */
    @Test
    void reportsEveryConfigurationFailureInOneException() throws Exception {
        Setup setup = new Setup();
        setup.values = Map.of("sessionRef", "inline-secret-value", "maxRows", 5000);
        assertThatThrownBy(() -> setup.host().admit(setup.bindings(), setup.inspect(), TestToolRegistrar.class, HOST, STARTUP))
                .isInstanceOfSatisfying(ComponentHostException.class, e -> {
                    assertThat(e.code()).isEqualTo(HostFailureCode.CONFIG_REQUIRED_MISSING);
                    assertThat(e.subject()).isEqualTo("urn:contract:a@1.0.0");
                    assertThat(e.getMessage()).contains("CONFIG_REQUIRED_MISSING [accountRef]", "CONFIG_INVALID [maxRows]",
                            "CONFIG_SECRET_INLINE [sessionRef]").doesNotContain("inline-secret-value");
                });
    }

    /** Every stage-2 check refuses with its own code and leaves nothing registered. */
    @Test
    void refusesInStageTwoAndLeavesNothingRegistered() throws Exception {
        refused(HostFailureCode.COMPONENT_CLAIM_MISMATCH, setup -> HostedSwitches.claimVersion = "9.9.9");
        refused(HostFailureCode.HOST_DEPENDENCY_UNMET, setup -> HostedSwitches.hostDependencies = Map.of());
        refused(HostFailureCode.HOST_DEPENDENCY_UNMET, setup -> setup.ports = ports(Map.of()));
        refused(HostFailureCode.REGISTRAR_SHAPE_UNSUPPORTED, setup -> HostedSwitches.supportShape = false);
        refused(HostFailureCode.COMPONENT_ACTIVATION_FAILED, setup -> HostedSwitches.factoryThrows = true);
        refused(HostFailureCode.COMPONENT_ACTIVATION_FAILED, setup -> HostedSwitches.hostDependencies = null);
    }

    /** An unmet host dependency names every gap: the undeclared need and the unbound port type. */
    @Test
    void namesEveryUnmetHostDependency() throws Exception {
        Setup setup = new Setup();
        setup.packagedClaim = withNeeds(claim("tool.a", "tool.b"),
                List.of("host.audit@v1?owner=host", "host.session@v1?owner=host", "peer.search@v1"));
        setup.ports = ports(Map.of());
        assertThatThrownBy(() -> setup.host().admit(setup.bindings(), setup.inspect(), TestToolRegistrar.class, HOST, STARTUP))
                .isInstanceOfSatisfying(ComponentHostException.class, e -> {
                    assertThat(e.code()).isEqualTo(HostFailureCode.HOST_DEPENDENCY_UNMET);
                    assertThat(e.getMessage()).contains("host.audit: the host binds no " + OperationAuditSink.class.getName(),
                            "host.session: not covered by the activation").doesNotContain("peer.search");
                });
    }

    /** A failing event sink or a register-then-throw registrar leaves no registration, with either kind of broker. */
    @Test
    void neverLeavesAnOrphanedRegistration() throws Exception {
        for (boolean exceptionSafeBroker : List.of(true, false)) {
            // The broker's event sink fails after tool.b was registered: a host-side failure, not the component's.
            HostedSwitches.reset();
            Setup sinkFails = new Setup();
            sinkFails.exceptionSafeBroker = exceptionSafeBroker;
            sinkFails.failEventFor = "tool.b";
            assertThatThrownBy(() -> sinkFails.host().admit(sinkFails.bindings(), sinkFails.inspect(), TestToolRegistrar.class, HOST, STARTUP))
                    .isInstanceOfSatisfying(ComponentHostException.class,
                            e -> assertThat(e.code()).isEqualTo(HostFailureCode.ADMISSION_REJECTED));
            assertThat(HostedSwitches.registrars).singleElement().satisfies(r -> assertThat(r.tools()).isEmpty());

            // The component's registrar stores tool.b and then throws.
            HostedSwitches.reset();
            HostedSwitches.failRegisterFor = "tool.b";
            Setup registerFails = new Setup();
            registerFails.exceptionSafeBroker = exceptionSafeBroker;
            assertThatThrownBy(() -> registerFails.host().admit(registerFails.bindings(), registerFails.inspect(), TestToolRegistrar.class, HOST, STARTUP))
                    .isInstanceOfSatisfying(ComponentHostException.class, e -> {
                        assertThat(e.code()).isEqualTo(HostFailureCode.COMPONENT_ACTIVATION_FAILED);
                        assertThat(e.getCause()).hasMessage("register boom");
                    });
            assertThat(HostedSwitches.registrars).singleElement().satisfies(r -> assertThat(r.tools()).isEmpty());
        }
    }

    /** Rollback continues past a failing unregister and reports it as suppressed. */
    @Test
    void rollbackContinuesPastAFailingUnregister() throws Exception {
        HostedSwitches.failRegisterFor = "tool.b";
        HostedSwitches.failUnregisterFor = "tool.a";
        Setup setup = new Setup();
        assertThatThrownBy(() -> setup.host().admit(setup.bindings(), setup.inspect(), TestToolRegistrar.class, HOST, STARTUP))
                .isInstanceOfSatisfying(ComponentHostException.class, e -> {
                    assertThat(e.code()).isEqualTo(HostFailureCode.COMPONENT_ACTIVATION_FAILED);
                    assertThat(e.getSuppressed()).isNotEmpty().allSatisfy(s -> assertThat(s).hasMessage("unregister boom"));
                });
        assertThat(HostedSwitches.registrars).singleElement()
                .satisfies(r -> assertThat(r.tools()).as("tool.b was still removed").containsOnlyKeys("tool.a"));
    }

    /** close() removes what it can, reports what it could not, and leaves the host closed. */
    @Test
    void closeReportsAFailingUnregister() throws Exception {
        Setup setup = new Setup();
        InProcessComponentHost host = setup.host();
        host.admit(setup.bindings(), setup.inspect(), TestToolRegistrar.class, HOST, STARTUP);
        HostedSwitches.failUnregisterFor = "tool.a";

        assertThatThrownBy(host::close).isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).isNotEmpty());
        assertThat(HostedSwitches.registrars).singleElement().satisfies(r -> assertThat(r.tools()).containsOnlyKeys("tool.a"));
        host.close();
        assertThatThrownBy(() -> setup.artifacts.activate(setup.pin)).as("artifacts were closed").isInstanceOf(IllegalStateException.class);
    }

    /**
     * Regression: a registrar that throws an {@link Error} (here an {@code AssertionError}) cannot stop cleanup. During
     * a failed admission and during close, every other registration is still removed, the artifacts are closed and the
     * error is reported as suppressed.
     */
    @Test
    void cleanupContinuesPastAnErrorThrownByTheRegistrar() throws Exception {
        HostedSwitches.throwErrors = true;
        HostedSwitches.failRegisterFor = "tool.b";
        HostedSwitches.failUnregisterFor = "tool.a";
        Setup failing = new Setup();
        assertThatThrownBy(() -> failing.host().admit(failing.bindings(), failing.inspect(), TestToolRegistrar.class, HOST, STARTUP))
                .isInstanceOfSatisfying(ComponentHostException.class, e -> {
                    assertThat(e.code()).isEqualTo(HostFailureCode.COMPONENT_ACTIVATION_FAILED);
                    assertThat(e.getCause()).isInstanceOf(AssertionError.class).hasMessage("register boom");
                    assertThat(e.getSuppressed()).isNotEmpty()
                            .allSatisfy(s -> assertThat(s).isInstanceOf(AssertionError.class).hasMessage("unregister boom"));
                });
        assertThat(HostedSwitches.registrars).singleElement()
                .satisfies(r -> assertThat(r.tools()).as("tool.b was still removed").containsOnlyKeys("tool.a"));
        assertThatThrownBy(() -> failing.artifacts.activate(failing.pin)).as("artifacts were closed").isInstanceOf(IllegalStateException.class);

        HostedSwitches.reset();
        Setup closing = new Setup();
        InProcessComponentHost host = closing.host();
        host.admit(closing.bindings(), closing.inspect(), TestToolRegistrar.class, HOST, STARTUP);
        HostedSwitches.throwErrors = true;
        HostedSwitches.failUnregisterFor = "tool.b";

        assertThatThrownBy(host::close).isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).isNotEmpty().allSatisfy(s -> assertThat(s).isInstanceOf(AssertionError.class)));
        assertThat(HostedSwitches.registrars).singleElement()
                .satisfies(r -> assertThat(r.tools()).as("tool.a was still removed").containsOnlyKeys("tool.b"));
        assertThatThrownBy(() -> closing.artifacts.activate(closing.pin)).as("artifacts were closed").isInstanceOf(IllegalStateException.class);
    }

    /** Runs one refused admission and checks its code and that nothing stays registered. */
    private void refused(HostFailureCode code, Consumer<Setup> change) throws Exception {
        HostedSwitches.reset();
        Setup setup = new Setup();
        change.accept(setup);
        assertThatThrownBy(() -> setup.host().admit(setup.bindings(), setup.inspect(), TestToolRegistrar.class, HOST, STARTUP))
                .as("expected %s", code)
                .isInstanceOfSatisfying(ComponentHostException.class, e -> assertThat(e.code()).isEqualTo(code));
        assertThat(HostedSwitches.registrars).allSatisfy(r -> assertThat(r.tools()).isEmpty());
        if (setup.artifacts != null) {
            assertThatThrownBy(() -> setup.artifacts.activate(setup.pin)).as("artifacts were closed").isInstanceOf(IllegalStateException.class);
        }
    }

    /** One admission scenario: a component jar, its pin, two hosted contracts and their bindings, each adjustable. */
    private final class Setup {
        private final Path home;
        private Claim packagedClaim = claim("tool.a", "tool.b");
        private String claimHash;
        private String configYaml = CONFIG;
        private Map<String, Object> values = VALUES;
        private Party contractConsumer = HOST;
        private Party bindingConsumer = HOST;
        private String bindingProviderVersion = "1.0.0";
        private boolean enabled = true;
        private boolean pinContracts = true;
        private boolean omitBindings;
        private boolean remoteContract;
        private boolean exceptionSafeBroker = true;
        private String failEventFor;
        private HostPorts ports = ports(Map.of(OperationAuditSink.class, (OperationAuditSink) (event, context) -> { }));
        private ProviderArtifactPin pin;
        private VerifiedContracts contracts;
        private PinnedJarArtifacts artifacts;

        private Setup() throws IOException {
            home = Files.createTempDirectory(dir, "setup");
        }

        /** Builds the jars, pin and verified contracts once. */
        private void build() throws IOException {
            if (contracts != null) return;
            Map<String, byte[]> entries = new LinkedHashMap<>(classes);
            entries.put("META-INF/unfurl-catalog.yaml", HostFixtures.manifest(packagedClaim));
            entries.put(PinnedJarArtifacts.ACTIVATION_DECLARATION, "example.hosted.ToolActivation".getBytes(StandardCharsets.UTF_8));
            if (configYaml != null) entries.put("META-INF/unfurl-config.yaml", configYaml.getBytes(StandardCharsets.UTF_8));
            Path jar = ComponentJars.jar(home.resolve("component.jar"), entries, Map.of());
            ComponentJars.jar(home.resolve("stray.jar"), Map.of("stray.txt", new byte[]{1}), Map.of());
            pin = new ProviderArtifactPin(ComponentJars.pin("example:hosted:1.0.0", jar),
                    claimHash == null ? ClaimHash.canonical(packagedClaim) : claimHash, List.of());
            List<ContractSource> sources = new ArrayList<>();
            sources.add(HostFixtures.source("a.json", contract("urn:contract:a", "tool.a")));
            sources.add(HostFixtures.source("b.json", contract("urn:contract:b", "tool.b")));
            if (remoteContract) {
                sources.add(HostFixtures.source("remote.json", HostFixtures.contract("urn:contract:remote", "tool.remote",
                        Map.of(), new Transport(TransportKind.HTTP_JSON, Map.of()))));
            }
            contracts = VerifiedContracts.load(sources, HostFixtures.keys());
        }

        private CompositionContract contract(String id, String capability) {
            Map<String, Object> extensions = pinContracts && pin != null
                    ? HostFixtures.pinExtensions(pin.claimArtifact(), pin.claimHash()) : Map.of();
            CompositionContract base = HostFixtures.contract(id, capability, extensions);
            return new CompositionContract(base.contractId(), base.contractVersion(),
                    new Parties(contractConsumer, base.parties().provider()), base.binding(), base.dataMapping(),
                    base.transport(), base.expectations(), base.provenance(), base.trust(), base.invalidation(), base.metadata());
        }

        private PinnedJarArtifacts inspect() throws IOException {
            build();
            artifacts = PinnedJarArtifacts.inspect(List.of(home.resolve("component.jar"), home.resolve("stray.jar")), contracts,
                    SharedApi.of(InProcessComponentHostTest.class.getClassLoader(), Set.of()));
            return artifacts;
        }

        private Map<URI, RuntimeBinding> bindings() {
            Map<URI, RuntimeBinding> bindings = new LinkedHashMap<>();
            if (omitBindings) return bindings;
            for (String id : List.of("urn:contract:a", "urn:contract:b")) {
                bindings.put(URI.create(id), new RuntimeBinding(URI.create(id.replace("contract", "binding")), URI.create(id), "1.0.0",
                        new TargetEnvironment("test"),
                        new ProviderInstance(URI.create("urn:provider"), bindingProviderVersion, "provider", DeploymentKind.IN_PROCESS, null, null, null),
                        new ConsumerInstance(bindingConsumer.claimUri(), bindingConsumer.claimVersion(), "host"),
                        new RuntimePolicy(true, 1000, "test", true), new Configuration(values), new DeploymentControls(Map.of()),
                        new Lifecycle(enabled), null));
            }
            return bindings;
        }

        /** Builds the host over the real broker, or over a double whose accept does no cleanup of its own. */
        private InProcessComponentHost host() throws IOException {
            build();
            BrokerEventSink sink = (event, context) -> {
                events.add(event);
                if (event.type() == BrokerEventType.CAPABILITY_REGISTERED && event.capabilityName().equals(failEventFor)) {
                    throw new IllegalStateException("audit sink is down");
                }
            };
            if (exceptionSafeBroker) return new InProcessComponentHost(contracts, HostFixtures.keys(), sink, ports);
            CompositionBroker real = new DefaultCompositionBroker(contracts, new OfflineContractVerifier(), HostFixtures.keys(),
                    new ClaimValidator(), sink);
            return new InProcessComponentHost(contracts, new NaiveBroker(real, failEventFor), ports);
        }
    }

    /**
     * Broker double that is not exception-safe: it registers and then fails without unregistering, as a broker without
     * the accept fix would. Only the host's tracking registrar can clean up after it.
     */
    private record NaiveBroker(CompositionBroker real, String failAfterRegistering) implements CompositionBroker {
        @Override public Disposition present(Claim claim, String providerCapability, ExecutionContext context) {
            return real.present(claim, providerCapability, context);
        }

        @Override public RegistrationHandle accept(Disposition disposition, CapabilityRegistrar registrar,
                                                   ContractInvocableFactory factory, ExecutionContext context) {
            String capability = disposition.matchedContractId().toString().endsWith(":a") ? "tool.a" : "tool.b";
            CompositionContract contract = HostFixtures.contract(disposition.matchedContractId().toString(), capability, Map.of());
            ContractInvocable invocable = factory.create(contract, contract.binding(), context);
            registrar.register(capability, invocable, context);
            if (capability.equals(failAfterRegistering)) throw new IllegalStateException("audit sink is down");
            return new RegistrationHandle(contract.contractId(), contract.contractVersion(), URI.create("urn:provider"), "1.0.0",
                    List.of(capability));
        }

        @Override public void revoke(RegistrationHandle handle, CapabilityRegistrar registrar, ExecutionContext context) {
            real.revoke(handle, registrar, context);
        }

        @Override public void invalidate(RegistrationHandle handle, CapabilityRegistrar registrar, ExecutionContext context) {
            real.invalidate(handle, registrar, context);
        }
    }

    /** Host ports fixture: exactly the given services, nothing else. */
    private static HostPorts ports(Map<Class<?>, Object> services) {
        return new HostPorts() {
            @Override public <T> Optional<T> port(Class<T> type) {
                return Optional.ofNullable(type.cast(services.get(type)));
            }

            @Override public Set<Class<?>> boundTypes() {
                return services.keySet();
            }
        };
    }

    /** A valid provider claim offering the capabilities, with one host-owned need. */
    private static Claim claim(String... capabilities) {
        return withNeeds(Fixtures.validProviderClaim(capabilities), List.of("host.audit@v1?owner=host"));
    }

    private static Claim withNeeds(Claim claim, List<String> needs) {
        return new Claim(claim.identity(), claim.domain(), claim.refusals(), new Dependencies(needs), claim.offers(),
                claim.conflictResolution(), claim.negotiationSurface(), claim.integrationPorts(), claim.faults(), claim.metadata());
    }

    private static Claim withIdentityUri(Claim claim, String uri) {
        var id = claim.identity();
        return new Claim(new com.unfurl.dcp.claim.Identity(URI.create(uri), id.name(), id.kind(), id.version(), id.publisher(), id.publisherUri()),
                claim.domain(), claim.refusals(), claim.dependencies(), claim.offers(), claim.conflictResolution(),
                claim.negotiationSurface(), claim.integrationPorts(), claim.faults(), claim.metadata());
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }
}
