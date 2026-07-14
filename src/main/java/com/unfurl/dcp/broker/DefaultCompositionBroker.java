package com.unfurl.dcp.broker;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimValidator;
import com.unfurl.dcp.contract.Binding;
import com.unfurl.dcp.contract.ContractInvocableAdapter;
import com.unfurl.dcp.contract.FrozenContract;
import com.unfurl.dcp.spi.BrokerEventSink;
import com.unfurl.dcp.spi.CapabilityRegistrar;
import com.unfurl.dcp.spi.ContractInvocableFactory;
import com.unfurl.dcp.spi.ContractStore;
import com.unfurl.dcp.spi.NoopBrokerEventSink;
import com.unfurl.dcp.trust.OfflineContractVerifier;
import com.unfurl.dcp.trust.VerificationKeySet;
import com.unfurl.dcp.trust.VerificationResult;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.SchemaValidationReport;
import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.policy.ExecutionContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Strategy plus Ports & Adapters implementation of the DCP runtime broker. It owns the deterministic
 * plane-3 flow: validate claim, look up a frozen contract, verify the offline signature, and expose
 * exactly one accepted binding through host-provided SPI ports. The class is stateless except for its
 * injected collaborators and must not perform network, model, or service-loader work.
 */
public final class DefaultCompositionBroker implements CompositionBroker {
    private final ContractStore contractStore;
    private final OfflineContractVerifier verifier;
    private final VerificationKeySet keySet;
    private final ClaimValidator claimValidator;
    private final BrokerEventSink eventSink;

    /**
     * Constructor injection keeps all side-effecting collaborators host-owned. Null validators and
     * event sinks are replaced with safe defaults; stores, verifiers, and key sets are required
     * because broker decisions must be deterministic and auditable.
     */
    public DefaultCompositionBroker(
            ContractStore contractStore,
            OfflineContractVerifier verifier,
            VerificationKeySet keySet,
            ClaimValidator claimValidator,
            BrokerEventSink eventSink
    ) {
        this.contractStore = Objects.requireNonNull(contractStore, "contractStore");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.keySet = Objects.requireNonNull(keySet, "keySet");
        this.claimValidator = claimValidator == null ? new ClaimValidator() : claimValidator;
        this.eventSink = eventSink == null ? new NoopBrokerEventSink() : eventSink;
    }

    /**
     * Strategy method: turns a presented claim into a disposition. The only accepted path is a
     * structurally valid claim with a matching frozen contract whose signature verifies offline;
     * every other path is represented as a refusal reason.
     */
    @Override
    public Disposition present(Claim claim, String providerCapability, ExecutionContext context) {
        publish(BrokerEventType.CLAIM_PRESENTED, claim == null || claim.identity() == null ? null : claim.identity().uri(), null, null, null, context);
        SchemaValidationReport validation = claimValidator.validate(claim);
        if (!validation.valid()) {
            DispositionReason reason = validation.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code() == ErrorCode.DCP_VERSION_UNSUPPORTED)
                    ? DispositionReason.DCP_VERSION_UNSUPPORTED
                    : DispositionReason.CLAIM_MALFORMED;
            Disposition disposition = Disposition.refuse(reason, "claim validation failed", null);
            publish(BrokerEventType.DISPOSITION_REFUSED, claim == null || claim.identity() == null ? null : claim.identity().uri(), null, null, reason, context);
            return disposition;
        }

        String capability = requestedCapability(claim, providerCapability, context);
        if (capability == null) {
            return Disposition.refuse(
                    DispositionReason.CAPABILITY_AMBIGUOUS,
                    "provider capability is required for multi-offer claims",
                    null);
        }
        if (!offersCapability(claim, capability)) {
            publish(BrokerEventType.DISPOSITION_REFUSED, claim.identity().uri(), null, capability,
                    DispositionReason.CAPABILITY_NOT_REQUESTED, context);
            return Disposition.refuse(
                    DispositionReason.CAPABILITY_NOT_REQUESTED,
                    "provider claim does not offer capability: " + capability,
                    null);
        }

        return contractStore.findByProvider(claim.identity().uri(), claim.identity().version(), capability)
                .map(frozen -> verifiedDisposition(claim, frozen, capability, context))
                .orElseGet(() -> noMatchingContract(claim, capability, context));
    }

    /**
     * Adapter method: materializes the host executor for an accepted disposition. It re-fetches the
     * frozen contract by id/version and re-verifies its signature so callers cannot smuggle stale
     * or unverified contract objects into the capability surface.
     */
    @Override
    public RegistrationHandle accept(
            Disposition disposition,
            CapabilityRegistrar registrar,
            ContractInvocableFactory invocableFactory,
            ExecutionContext context
    ) {
        if (disposition == null
                || disposition.kind() != DispositionKind.ACCEPT
                || disposition.matchedContractId() == null
                || disposition.matchedContractVersion() == null) {
            throw new BrokerException(DispositionReason.BROKER_ACCEPT_INVALID, "accept requires an ACCEPT disposition with contract id/version");
        }
        FrozenContract frozen = contractStore.findById(disposition.matchedContractId(), disposition.matchedContractVersion())
                .orElseThrow(() -> new BrokerException(DispositionReason.CONTRACT_NOT_FOUND, "frozen contract not found"));
        if (!Objects.equals(frozen.contract().contractId(), disposition.matchedContractId())
                || !Objects.equals(frozen.contract().contractVersion(), disposition.matchedContractVersion())) {
            throw new BrokerException(DispositionReason.BROKER_ACCEPT_INVALID, "stale disposition does not match frozen contract");
        }
        VerificationResult verification = verifier.verify(frozen.signedContract(), keySet);
        if (!verification.valid()) {
            throw new BrokerException(DispositionReason.SIGNATURE_INVALID, "signature verification failed: " + verification.reason());
        }
        Binding binding = frozen.contract().binding();
        RegistrationHandle handle = new RegistrationHandle(
                frozen.contract().contractId(),
                frozen.contract().contractVersion(),
                frozen.contract().parties().provider().claimUri(),
                frozen.contract().parties().provider().claimVersion(),
                List.of(binding.providerCapability()));
        ContractInvocable delegate = invocableFactory.create(frozen.contract(), binding, context);
        ContractInvocable adapted = new ContractInvocableAdapter(frozen, handle.stableId(), delegate);
        registrar.register(binding.providerCapability(), adapted, context);
        publish(BrokerEventType.CAPABILITY_REGISTERED, handle.claimUri(), handle.contractId(), binding.providerCapability(), DispositionReason.MATCH_FOUND, context);
        return handle;
    }

    /**
     * Lifecycle method: unregisters all capabilities exposed by a prior accept call. The broker
     * deliberately emits one revoke event per capability so downstream audit can match registrar
     * mutations to the registration handle.
     */
    @Override
    public void revoke(RegistrationHandle handle, CapabilityRegistrar registrar, ExecutionContext context) {
        if (handle == null) {
            return;
        }
        for (String capability : handle.exposedCapabilityNames()) {
            registrar.unregister(capability, context);
            publish(BrokerEventType.CAPABILITY_REVOKED, handle.claimUri(), handle.contractId(), capability, null, context);
        }
    }

    /**
     * Runtime invalidation method: converts a host-detected contract assumption violation into an
     * audit event and revocation. It never performs design-time renegotiation or self-healing.
     */
    @Override
    public void invalidate(RegistrationHandle handle, CapabilityRegistrar registrar, ExecutionContext context) {
        if (handle == null) {
            return;
        }
        publish(BrokerEventType.CONTRACT_INVALIDATED, handle.claimUri(), handle.contractId(), null, DispositionReason.CONTRACT_INVALIDATED, context);
        revoke(handle, registrar, context);
    }

    /**
     * Verification helper: maps the frozen contract signature result into the accepted/refused
     * disposition shape while preserving correlation through the event sink.
     */
    private Disposition verifiedDisposition(Claim claim, FrozenContract frozen, String providerCapability, ExecutionContext context) {
        VerificationResult result = verifier.verify(frozen.signedContract(), keySet);
        if (!result.valid()) {
            publish(BrokerEventType.DISPOSITION_REFUSED, claim.identity().uri(), frozen.contract().contractId(), providerCapability, DispositionReason.SIGNATURE_INVALID, context);
            return Disposition.refuse(DispositionReason.SIGNATURE_INVALID, result.reason(), null);
        }
        publish(BrokerEventType.DISPOSITION_ACCEPTED, claim.identity().uri(), frozen.contract().contractId(), frozen.contract().binding().providerCapability(), DispositionReason.MATCH_FOUND, context);
        return Disposition.accept(frozen.contract().contractId(), frozen.contract().contractVersion());
    }

    /**
     * Refusal helper: uses fabric's precomputed ownership redirection from the provider claim when
     * no frozen contract exists, keeping runtime behavior deterministic and explanation-bearing.
     */
    private Disposition noMatchingContract(Claim claim, String providerCapability, ExecutionContext context) {
        String redirection = claim.refusals().stream()
                .map(refusal -> refusal.ownedBy())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        publish(BrokerEventType.DISPOSITION_REFUSED, claim.identity().uri(), null, providerCapability, DispositionReason.NO_MATCHING_CONTRACT, context);
        return Disposition.refuse(DispositionReason.NO_MATCHING_CONTRACT, "no matching frozen contract for capability: " + providerCapability, redirection);
    }

    /**
     * Capability selector: preserves the ergonomic single-offer path while forcing multi-offer
     * providers to name the exact offer being accepted.
     */
    private String requestedCapability(Claim claim, String providerCapability, ExecutionContext context) {
        if (providerCapability != null && !providerCapability.isBlank()) {
            return providerCapability;
        }
        if (claim.offers().size() == 1 && claim.offers().get(0) != null) {
            return claim.offers().get(0).capability();
        }
        publish(BrokerEventType.DISPOSITION_REFUSED, claim.identity().uri(), null, null,
                DispositionReason.CAPABILITY_AMBIGUOUS, context);
        return null;
    }

    /**
     * Offer guard: ensures the runtime binding cannot accept a capability outside the validated
     * provider claim.
     */
    private boolean offersCapability(Claim claim, String providerCapability) {
        return claim.offers().stream()
                .filter(Objects::nonNull)
                .anyMatch(offer -> Objects.equals(offer.capability(), providerCapability));
    }

    /**
     * Event adapter: emits metadata-only broker events through the injected sink. Payloads omit full
     * claims/contracts by invariant so hosts can opt into richer audit outside this library.
     */
    private void publish(BrokerEventType type, java.net.URI claimUri, java.net.URI contractId, String capability, DispositionReason reason, ExecutionContext context) {
        eventSink.publish(new BrokerEvent(type, claimUri, contractId, capability, correlationId(context), reason, Instant.now(), Map.of()), context);
    }

    /**
     * Context helper: extracts the optional correlation id without forcing callers to allocate a
     * synthetic execution context for tests or offline validation.
     */
    private String correlationId(ExecutionContext context) {
        return context == null ? null : context.correlationId();
    }
}
