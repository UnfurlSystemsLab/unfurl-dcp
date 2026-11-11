package com.unfurl.dcp.host;

import com.unfurl.dcp.broker.CompositionBroker;
import com.unfurl.dcp.broker.DefaultCompositionBroker;
import com.unfurl.dcp.broker.Disposition;
import com.unfurl.dcp.broker.DispositionKind;
import com.unfurl.dcp.broker.RegistrationHandle;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimHash;
import com.unfurl.dcp.claim.ClaimValidator;
import com.unfurl.dcp.claim.Dependencies;
import com.unfurl.dcp.componentconfig.ComponentConfigurationException;
import com.unfurl.dcp.componentconfig.ComponentConfigurationSchema;
import com.unfurl.dcp.componentconfig.ComponentConfigurationValidator;
import com.unfurl.dcp.componentconfig.EffectiveConfiguration;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.Party;
import com.unfurl.dcp.contract.TransportKind;
import com.unfurl.dcp.host.ComponentScope.ComponentCodeFailure;
import com.unfurl.dcp.host.ComponentScope.TrackingRegistrar;
import com.unfurl.dcp.host.HostReport.AdmittedOperation;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.runtimebinding.RuntimeBindingValidator;
import com.unfurl.dcp.spi.BrokerEventSink;
import com.unfurl.dcp.spi.CapabilityRegistrar;
import com.unfurl.dcp.spi.ContractInvocableFactory;
import com.unfurl.dcp.trust.OfflineContractVerifier;
import com.unfurl.dcp.trust.VerificationKeySet;
import com.unfurl.dcp.validation.SchemaValidationReport;
import com.unfurl.substrate.policy.ExecutionContext;
import com.unfurl.substrate.ports.host.ConfigResolver;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Facade: hosts one verified set of in-process components, all or nothing (HLD-C §6.1; LLD, <em>In-Process Component
 * Host</em>). It is the generic mechanism every host product shares; a host supplies only its bound {@link HostPorts},
 * its registrar shape and its own identity.
 *
 * <p>Admission runs in stages, each over every hosted contract (ordered by contract id) before the next starts:
 * <ul>
 *   <li><b>Stage 0</b> already happened: only {@link VerifiedContracts} are accepted, and the broker is built over that
 *       same store.</li>
 *   <li><b>Stage 1, data only:</b> pin, packaged claim identity and canonical hash, consumer equals this host, runtime
 *       binding valid and enabled, effective configuration, and the broker's {@code present}. No component code has
 *       run when stage 1 completes.</li>
 *   <li><b>Stage 2, component code:</b> activation, coverage of every {@code owner=host} need by bound host ports, the
 *       registrar of the host's shape, then the broker's {@code accept}.</li>
 * </ul>
 * Any failure rolls back every attempted registration through a tracking registrar, continues past individual
 * rollback failures (attached as suppressed), closes the artifacts and throws {@link ComponentHostException}.
 * {@link #close()} uses the same rollback path.
 */
public final class InProcessComponentHost implements AutoCloseable {
    private static final String HOST_OWNER = "host";

    private enum State { NEW, ADMITTED, CLOSED }

    /** One admitted component at runtime: its scope, registrar and the handles the broker returned. */
    private static final class Hosted {
        private final TrackingRegistrar tracking;
        private final List<RegistrationHandle> handles = new ArrayList<>();

        private Hosted(TrackingRegistrar tracking) {
            this.tracking = tracking;
        }
    }

    /** A hosted contract that has passed every stage-1 check. */
    private record Checked(VerifiedContract verified, ProviderArtifactPin pin, Claim claim, RuntimeBinding binding,
                           EffectiveConfiguration configuration, Disposition disposition) { }

    /** The context handed to a component's activation for one operation. */
    private record Context(CompositionContract contract, RuntimeBinding runtimeBinding, HostPorts host,
                           EffectiveConfiguration configuration) implements ActivationContext { }

    private final VerifiedContracts contracts;
    private final CompositionBroker broker;
    private final HostPorts host;
    private final List<Hosted> components = new ArrayList<>();
    private State state = State.NEW;
    private PinnedJarArtifacts artifacts;
    private ExecutionContext startup;

    /**
     * Builds the host with its own broker over {@code contracts} and {@code keys}, so the broker cannot see another
     * contract store.
     *
     * @param contracts the verified contract set
     * @param keys      the host's trusted verification keys
     * @param events    the broker's audit sink; null publishes nothing
     * @param host      the neutral host services this host has bound
     */
    public InProcessComponentHost(VerifiedContracts contracts, VerificationKeySet keys, BrokerEventSink events, HostPorts host) {
        this(contracts, new DefaultCompositionBroker(contracts, new OfflineContractVerifier(),
                Objects.requireNonNull(keys, "keys"), new ClaimValidator(), events), host);
    }

    /** Testing seam: runs the host over a supplied broker, such as a double that is not exception-safe. */
    InProcessComponentHost(VerifiedContracts contracts, CompositionBroker broker, HostPorts host) {
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.broker = Objects.requireNonNull(broker, "broker");
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Checks, covers and admits every verified contract whose provider is hosted in-process.
     *
     * @param bindings       the trusted runtime bindings by contract id
     * @param artifacts      the inspected pinned jars; this host takes ownership and closes them
     * @param registrarShape the registrar interface this host exposes operations through
     * @param hostIdentity   this host's own identity from its packaged claim; every admitted contract's consumer party
     *                       and binding consumer must equal it
     * @param startup        the trusted context admission runs under; also used for rollback and close
     * @return the report of what was admitted
     * @throws ComponentHostException on any failure, after every attempted registration has been removed
     * @throws IllegalStateException  when called twice or after {@link #close()}
     */
    public synchronized <R extends CapabilityRegistrar> HostReport<R> admit(
            Map<URI, RuntimeBinding> bindings, PinnedJarArtifacts artifacts, Class<R> registrarShape,
            Party hostIdentity, ExecutionContext startup) {
        if (state != State.NEW) throw new IllegalStateException("a component host admits once");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(registrarShape, "registrarShape");
        Objects.requireNonNull(hostIdentity, "hostIdentity");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.startup = Objects.requireNonNull(startup, "startup");
        try {
            List<VerifiedContract> hosted = new ArrayList<>();
            List<String> notHosted = new ArrayList<>();
            for (VerifiedContract verified : contracts.all()) {
                boolean inProcess = verified.contract().transport() != null
                        && verified.contract().transport().kind() == TransportKind.IN_PROCESS;
                if (inProcess) hosted.add(verified);
                else notHosted.add(verified.id());
            }
            List<Checked> checked = checkData(hosted, bindings, hostIdentity);
            HostReport<R> report = activateAndAccept(checked, registrarShape, hostIdentity, notHosted);
            state = State.ADMITTED;
            return report;
        } catch (RuntimeException | Error failure) {
            rollback(failure);
            state = State.CLOSED;
            throw failure;
        }
    }

    /** Removes every registration through the rollback path and closes the artifacts; idempotent. */
    @Override
    public synchronized void close() {
        if (state == State.CLOSED) return;
        boolean admitted = state == State.ADMITTED;
        state = State.CLOSED;
        if (!admitted) return;
        IllegalStateException failure = new IllegalStateException("the component host did not close cleanly");
        rollback(failure);
        if (failure.getSuppressed().length > 0) throw failure;
    }

    /** Stage 1 for every hosted contract: data checks only, in the LLD's step order 1 to 5. */
    private List<Checked> checkData(List<VerifiedContract> hosted, Map<URI, RuntimeBinding> bindings, Party hostIdentity) {
        List<Checked> checked = new ArrayList<>();
        Map<ProviderArtifactPin, Optional<ComponentConfigurationSchema>> schemas = new HashMap<>();
        RuntimeBindingValidator bindingValidator = new RuntimeBindingValidator();
        for (VerifiedContract verified : hosted) {
            CompositionContract contract = verified.contract();
            String subject = verified.id();
            Party provider = contract.parties().provider();

            ProviderArtifactPin pin = verified.pin().orElseThrow(() -> new ComponentHostException(
                    HostFailureCode.PROVIDER_PIN_MISSING, subject, "the contract pins no provider artifact"));
            Claim claim = artifacts.packagedClaim(pin).orElseThrow(() -> new ComponentHostException(
                    HostFailureCode.COMPONENT_ARTIFACT_MISSING, subject,
                    "no verified artifact carries the claim of " + pin.claimArtifact().coordinates()));
            if (!sameIdentity(claim.identity().uri(), claim.identity().version(), provider)) {
                throw new ComponentHostException(HostFailureCode.COMPONENT_CLAIM_MISMATCH, subject,
                        "the packaged claim is " + claim.identity().uri() + "@" + claim.identity().version()
                                + " but the contract's provider is " + describe(provider));
            }
            if (!ClaimHash.canonical(claim).equals(pin.claimHash())) {
                throw new ComponentHostException(HostFailureCode.CLAIM_HASH_MISMATCH, subject,
                        "the packaged claim's canonical hash differs from the pinned claimHash");
            }
            Party consumer = contract.parties().consumer();
            if (consumer == null || !sameIdentity(consumer.claimUri(), consumer.claimVersion(), hostIdentity)) {
                throw new ComponentHostException(HostFailureCode.CONSUMER_MISMATCH, subject,
                        "the contract's consumer is " + describe(consumer) + " but this host is " + describe(hostIdentity));
            }

            RuntimeBinding binding = bindings.get(contract.contractId());
            if (binding == null) {
                throw new ComponentHostException(HostFailureCode.RUNTIME_BINDING_INVALID, subject, "no runtime binding was supplied");
            }
            // Checked before general validation so a binding made for another consumer is named as exactly that.
            if (binding.consumerInstance() == null || !sameIdentity(binding.consumerInstance().componentUri(),
                    binding.consumerInstance().componentVersion(), hostIdentity)) {
                throw new ComponentHostException(HostFailureCode.CONSUMER_MISMATCH, subject,
                        "the runtime binding's consumer instance is not this host, " + describe(hostIdentity));
            }
            SchemaValidationReport bindingReport = bindingValidator.validate(binding, contract);
            if (!bindingReport.valid()) {
                throw new ComponentHostException(HostFailureCode.RUNTIME_BINDING_INVALID, subject,
                        bindingReport.diagnostics().stream().map(d -> d.code() + " at " + d.fieldPath())
                                .collect(Collectors.joining("; ")));
            }
            if (binding.lifecycle() == null || !binding.lifecycle().enabled()) {
                throw new ComponentHostException(HostFailureCode.RUNTIME_BINDING_DISABLED, subject, "the runtime binding is disabled");
            }
            EffectiveConfiguration configuration = configuration(pin, binding, schemas, subject);

            Disposition disposition = broker.present(claim, contract.binding().providerCapability(), startup);
            if (disposition == null || disposition.kind() != DispositionKind.ACCEPT
                    || !contract.contractId().equals(disposition.matchedContractId())
                    || !contract.contractVersion().equals(disposition.matchedContractVersion())) {
                throw new ComponentHostException(HostFailureCode.ADMISSION_REJECTED, subject,
                        "the broker did not accept the provider for this contract: "
                                + (disposition == null ? "no disposition" : disposition.reasonCode() + ", " + disposition.rationale()));
            }
            checked.add(new Checked(verified, pin, claim, binding, configuration, disposition));
        }
        return checked;
    }

    /** Builds one contract's effective configuration from the packaged file and its binding (LLD, Component configuration). */
    private EffectiveConfiguration configuration(ProviderArtifactPin pin, RuntimeBinding binding,
                                                 Map<ProviderArtifactPin, Optional<ComponentConfigurationSchema>> schemas,
                                                 String subject) {
        try {
            Optional<ComponentConfigurationSchema> schema = schemas.get(pin);
            if (schema == null) {
                schema = artifacts.packagedConfiguration(pin).map(ComponentConfigurationSchema::parse);
                schemas.put(pin, schema);
            }
            if (schema.isEmpty()) return EffectiveConfiguration.unmanaged(binding.configuration());
            return ComponentConfigurationValidator.merge(schema.get(), binding.configuration(),
                    host.port(ConfigResolver.class).orElse(null), startup);
        } catch (ComponentConfigurationException invalid) {
            throw new ComponentHostException(HostFailureCode.valueOf(invalid.failures().get(0).code().name()), subject,
                    invalid.getMessage(), invalid);
        }
    }

    /** Stage 2 for every checked contract: component code, in the LLD's step order 6 to 9. */
    private <R extends CapabilityRegistrar> HostReport<R> activateAndAccept(
            List<Checked> checked, Class<R> registrarShape, Party hostIdentity, List<String> notHosted) {
        Map<ProviderArtifactPin, Hosted> byPin = new HashMap<>();
        Map<ProviderArtifactPin, ComponentActivation> activations = new HashMap<>();
        Map<ProviderArtifactPin, ComponentScope> scopes = new HashMap<>();
        Map<String, R> registrars = new LinkedHashMap<>();
        List<AdmittedOperation> operations = new ArrayList<>();
        for (Checked item : checked) {
            CompositionContract contract = item.verified().contract();
            String subject = item.verified().id();
            Party provider = contract.parties().provider();
            try {
                Hosted hosted = byPin.get(item.pin());
                if (hosted == null) {
                    ComponentActivation activation = artifacts.activate(item.pin());
                    ComponentScope scope = new ComponentScope(activation.getClass().getClassLoader());
                    requireSameClaim(activation, scope, item.claim(), subject);
                    requireCoverage(activation, scope, item.claim(), subject);
                    R registrar = scope.admissionCall("the activation's registrar method failed",
                                    () -> Objects.requireNonNull(activation.registrar(registrarShape, hostIdentity), "registrar"))
                            .filter(registrarShape::isInstance)
                            .orElseThrow(() -> new ComponentHostException(HostFailureCode.REGISTRAR_SHAPE_UNSUPPORTED,
                                    subject, "the component offers no registrar of shape " + registrarShape.getName()));
                    hosted = new Hosted(new TrackingRegistrar(registrar, scope));
                    // Known to rollback before anything can be registered through it.
                    components.add(hosted);
                    byPin.put(item.pin(), hosted);
                    activations.put(item.pin(), activation);
                    scopes.put(item.pin(), scope);
                    registrars.put(describe(provider), registrar);
                }
                ComponentScope scope = scopes.get(item.pin());
                ComponentActivation activation = activations.get(item.pin());
                Context context = new Context(contract, item.binding(), host, item.configuration());
                ContractInvocableFactory factory = scope.admissionCall("the activation's factory method failed",
                        () -> Objects.requireNonNull(activation.factory(context), "factory"));
                hosted.handles.add(broker.accept(item.disposition(), hosted.tracking, scope.scoped(factory), startup));
            } catch (ComponentHostException failure) {
                throw failure;
            } catch (ComponentCodeFailure componentFailure) {
                throw new ComponentHostException(HostFailureCode.COMPONENT_ACTIVATION_FAILED, subject,
                        componentFailure.getMessage(), componentFailure.getCause());
            } catch (RuntimeException | Error rejected) {
                // Not component code: the broker refused (BrokerException) or a host collaborator such as its event sink failed.
                throw new ComponentHostException(HostFailureCode.ADMISSION_REJECTED, subject,
                        "the broker could not accept the operation: " + rejected, rejected);
            }
            operations.add(new AdmittedOperation(provider.claimUri(), provider.claimVersion(),
                    contract.binding().providerCapability(), contract.contractId(), contract.contractVersion(),
                    item.configuration().redacted(), item.configuration().origins()));
        }
        return new HostReport<>(registrars, operations, artifacts.ignoredArtifacts(), notHosted);
    }

    /** Step 6: the activation must speak for the same claim the artifact packages. */
    private static void requireSameClaim(ComponentActivation activation, ComponentScope scope, Claim claim, String subject) {
        URI uri = scope.admissionCall("the activation's claimUri method failed", activation::claimUri);
        String version = scope.admissionCall("the activation's claimVersion method failed", activation::claimVersion);
        if (!claim.identity().uri().equals(uri) || !claim.identity().version().equals(version)) {
            throw new ComponentHostException(HostFailureCode.COMPONENT_CLAIM_MISMATCH, subject,
                    "the activation speaks for " + uri + "@" + version + " but the packaged claim is "
                            + claim.identity().uri() + "@" + claim.identity().version());
        }
    }

    /**
     * Step 7: every {@code owner=host} need in the packaged claim must be declared by the activation, and every host
     * port type it lists must be bound. Optional {@code owner=host} dependencies are not required. All gaps are
     * collected into one {@code HOST_DEPENDENCY_UNMET}.
     */
    private void requireCoverage(ComponentActivation activation, ComponentScope scope, Claim claim, String subject) {
        Map<String, Set<Class<?>>> declared = scope.admissionCall("the activation's hostDependencies method failed",
                () -> Objects.requireNonNull(activation.hostDependencies(), "hostDependencies"));
        Set<Class<?>> bound = host.boundTypes();
        Set<String> gaps = new TreeSet<>();
        List<String> needs = claim.dependencies() == null ? List.of() : claim.dependencies().needs();
        for (String need : needs) {
            if (!HOST_OWNER.equals(Dependencies.ownerOf(need))) continue;
            String capability = Dependencies.capabilityOf(need);
            Set<Class<?>> types = capability == null ? null : declared.get(capability);
            if (types == null) {
                gaps.add(capability + ": not covered by the activation");
                continue;
            }
            for (Class<?> type : types) {
                if (!bound.contains(type)) gaps.add(capability + ": the host binds no " + type.getName());
            }
        }
        if (!gaps.isEmpty()) {
            throw new ComponentHostException(HostFailureCode.HOST_DEPENDENCY_UNMET, subject, String.join("; ", gaps));
        }
    }

    /**
     * Rollback (LLD, Rollback and close): unregister every attempted registration in reverse, revoke every returned
     * handle through the broker so revocation events are emitted, then close the artifacts. Every step runs even if an
     * earlier one throws; each failure is attached to {@code primary} as suppressed.
     */
    private void rollback(Throwable primary) {
        for (int c = components.size() - 1; c >= 0; c--) {
            TrackingRegistrar tracking = components.get(c).tracking;
            List<String> attempted = tracking.attempted();
            for (int i = attempted.size() - 1; i >= 0; i--) {
                String capability = attempted.get(i);
                attempt(() -> tracking.unregister(capability, startup), primary);
            }
        }
        for (int c = components.size() - 1; c >= 0; c--) {
            Hosted hosted = components.get(c);
            for (int i = hosted.handles.size() - 1; i >= 0; i--) {
                RegistrationHandle handle = hosted.handles.get(i);
                attempt(() -> broker.revoke(handle, hosted.tracking, startup), primary);
            }
        }
        components.clear();
        if (artifacts != null) attempt(artifacts::close, primary);
    }

    /**
     * Runs one rollback step. Whatever it throws, including any {@link Error} such as an {@code AssertionError} from a
     * component's registrar, is attached to {@code primary} as suppressed and never stops the remaining steps: a
     * partly rolled-back host would keep operations registered and artifacts open with no way to retry.
     */
    private static void attempt(Runnable step, Throwable primary) {
        try {
            step.run();
        } catch (Throwable failure) {
            if (failure != primary) primary.addSuppressed(failure);
        }
    }

    private static boolean sameIdentity(URI uri, String version, Party party) {
        return party != null && Objects.equals(uri, party.claimUri()) && Objects.equals(version, party.claimVersion());
    }

    private static String describe(Party party) {
        return party == null ? "absent" : party.claimUri() + "@" + party.claimVersion();
    }
}
