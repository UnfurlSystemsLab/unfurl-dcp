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

public final class DefaultCompositionBroker implements CompositionBroker {
    private final ContractStore contractStore;
    private final OfflineContractVerifier verifier;
    private final VerificationKeySet keySet;
    private final ClaimValidator claimValidator;
    private final BrokerEventSink eventSink;

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

    @Override
    public Disposition present(Claim claim, ExecutionContext context) {
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

        return contractStore.findByProvider(claim.identity().uri(), claim.identity().version())
                .map(frozen -> verifiedDisposition(claim, frozen, context))
                .orElseGet(() -> noMatchingContract(claim, context));
    }

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

    private Disposition verifiedDisposition(Claim claim, FrozenContract frozen, ExecutionContext context) {
        VerificationResult result = verifier.verify(frozen.signedContract(), keySet);
        if (!result.valid()) {
            publish(BrokerEventType.DISPOSITION_REFUSED, claim.identity().uri(), frozen.contract().contractId(), null, DispositionReason.SIGNATURE_INVALID, context);
            return Disposition.refuse(DispositionReason.SIGNATURE_INVALID, result.reason(), null);
        }
        publish(BrokerEventType.DISPOSITION_ACCEPTED, claim.identity().uri(), frozen.contract().contractId(), frozen.contract().binding().providerCapability(), DispositionReason.MATCH_FOUND, context);
        return Disposition.accept(frozen.contract().contractId(), frozen.contract().contractVersion());
    }

    private Disposition noMatchingContract(Claim claim, ExecutionContext context) {
        String redirection = claim.refusals().stream()
                .map(refusal -> refusal.ownedBy())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        publish(BrokerEventType.DISPOSITION_REFUSED, claim.identity().uri(), null, null, DispositionReason.NO_MATCHING_CONTRACT, context);
        return Disposition.refuse(DispositionReason.NO_MATCHING_CONTRACT, "no matching frozen contract", redirection);
    }

    private void publish(BrokerEventType type, java.net.URI claimUri, java.net.URI contractId, String capability, DispositionReason reason, ExecutionContext context) {
        eventSink.publish(new BrokerEvent(type, claimUri, contractId, capability, correlationId(context), reason, Instant.now(), Map.of()), context);
    }

    private String correlationId(ExecutionContext context) {
        return context == null ? null : context.correlationId();
    }
}
