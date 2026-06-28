package com.unfurl.dcp.broker;

import com.unfurl.dcp.testing.EchoContractInvocableFactory;
import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.dcp.testing.InMemoryCapabilityRegistrar;
import com.unfurl.dcp.testing.InMemoryContractStore;
import com.unfurl.dcp.testing.RecordingBrokerEventSink;
import com.unfurl.dcp.trust.OfflineContractVerifier;
import com.unfurl.dcp.trust.VerificationKey;
import com.unfurl.dcp.trust.VerificationKeySet;
import com.unfurl.substrate.policy.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.unfurl.dcp.claim.*;
import com.unfurl.dcp.contract.FrozenContract;
import com.unfurl.dcp.spi.ContractStore;

import java.net.URI;
import java.util.Optional;

class DefaultCompositionBrokerTest {
    @Test
    void presentAcceptsMatchingVerifiedContractAndRegistersOnAccept() throws Exception {
        KeyPair keyPair = keyPair();
        InMemoryContractStore store = new InMemoryContractStore();
        store.put(Fixtures.frozenContract(keyPair));
        InMemoryCapabilityRegistrar registrar = new InMemoryCapabilityRegistrar();
        RecordingBrokerEventSink events = new RecordingBrokerEventSink();
        DefaultCompositionBroker broker = broker(store, keyPair, events);

        Disposition disposition = broker.present(Fixtures.validProviderClaim(), ExecutionContext.empty());
        RegistrationHandle handle = broker.accept(disposition, registrar, new EchoContractInvocableFactory(), ExecutionContext.empty());

        assertThat(disposition.kind()).isEqualTo(DispositionKind.ACCEPT);
        assertThat(handle.exposedCapabilityNames()).containsExactly("answer.search");
        assertThat(registrar.registration("answer.search")).isPresent();
        assertThat(events.events()).extracting(BrokerEvent::type)
                .contains(BrokerEventType.DISPOSITION_ACCEPTED, BrokerEventType.CAPABILITY_REGISTERED);
    }

    @Test
    void presentRefusesWhenNoContractMatchesAndReturnsPrecomputedRedirection() throws Exception {
        RecordingBrokerEventSink events = new RecordingBrokerEventSink();
        DefaultCompositionBroker broker = broker(new InMemoryContractStore(), keyPair(), events);

        Disposition disposition = broker.present(Fixtures.validProviderClaim(), context("corr-no-match"));

        assertThat(disposition.kind()).isEqualTo(DispositionKind.REFUSE);
        assertThat(disposition.reasonCode()).isEqualTo(DispositionReason.NO_MATCHING_CONTRACT);
        assertThat(disposition.redirection()).isEqualTo("urn:finance");
        assertThat(events.events()).allSatisfy(event -> assertThat(event.metadata()).isEmpty());
        assertThat(events.events()).extracting(BrokerEvent::correlationId).contains("corr-no-match");
    }

    @Test
    void presentRefusesMalformedAndUnsupportedClaims() throws Exception {
        DefaultCompositionBroker broker = broker(new InMemoryContractStore(), keyPair(), new RecordingBrokerEventSink());

        assertThat(broker.present(malformedClaim(), ExecutionContext.empty()).reasonCode())
                .isEqualTo(DispositionReason.CLAIM_MALFORMED);
        assertThat(broker.present(claimWithDcpVersion("0.1.0"), ExecutionContext.empty()).reasonCode())
                .isEqualTo(DispositionReason.DCP_VERSION_UNSUPPORTED);
    }

    @Test
    void presentRefusesWhenSignatureVerificationFails() throws Exception {
        InMemoryContractStore store = new InMemoryContractStore();
        store.put(Fixtures.frozenContract(keyPair()));
        DefaultCompositionBroker broker = broker(store, keyPair(), new RecordingBrokerEventSink());

        Disposition disposition = broker.present(Fixtures.validProviderClaim(), ExecutionContext.empty());

        assertThat(disposition.kind()).isEqualTo(DispositionKind.REFUSE);
        assertThat(disposition.reasonCode()).isEqualTo(DispositionReason.SIGNATURE_INVALID);
    }

    @Test
    void acceptRejectsRefuseDispositionBeforeRegistering() throws Exception {
        InMemoryContractStore store = new InMemoryContractStore();
        InMemoryCapabilityRegistrar registrar = new InMemoryCapabilityRegistrar();
        DefaultCompositionBroker broker = broker(store, keyPair(), new RecordingBrokerEventSink());

        assertThatThrownBy(() -> broker.accept(
                Disposition.refuse(DispositionReason.NO_MATCHING_CONTRACT, "no match", null),
                registrar,
                new EchoContractInvocableFactory(),
                ExecutionContext.empty()))
                .isInstanceOf(BrokerException.class)
                .extracting("reason")
                .isEqualTo(DispositionReason.BROKER_ACCEPT_INVALID);

        assertThat(registrar.registration("answer.search")).isEmpty();
    }

    @Test
    void acceptFailsWhenFrozenContractCannotBeReFetched() throws Exception {
        DefaultCompositionBroker broker = broker(new InMemoryContractStore(), keyPair(), new RecordingBrokerEventSink());

        assertThatThrownBy(() -> broker.accept(
                Disposition.accept(java.net.URI.create("urn:missing"), "1.0.0"),
                new InMemoryCapabilityRegistrar(),
                new EchoContractInvocableFactory(),
                ExecutionContext.empty()))
                .isInstanceOf(BrokerException.class)
                .extracting("reason")
                .isEqualTo(DispositionReason.CONTRACT_NOT_FOUND);
    }

    @Test
    void acceptRejectsStaleDispositionBeforeRegistering() throws Exception {
        KeyPair keyPair = keyPair();
        FrozenContract frozen = Fixtures.frozenContract(keyPair);
        ContractStore staleStore = new ContractStore() {
            @Override
            public Optional<FrozenContract> findByProvider(URI providerClaimUri, String providerClaimVersion) {
                return Optional.of(frozen);
            }

            @Override
            public Optional<FrozenContract> findById(URI contractId, String contractVersion) {
                return Optional.of(frozen);
            }
        };
        InMemoryCapabilityRegistrar registrar = new InMemoryCapabilityRegistrar();
        DefaultCompositionBroker broker = new DefaultCompositionBroker(
                staleStore,
                new OfflineContractVerifier(),
                VerificationKeySet.of(List.of(new VerificationKey("test-key", keyPair.getPublic()))),
                null,
                new RecordingBrokerEventSink());

        assertThatThrownBy(() -> broker.accept(
                Disposition.accept(URI.create("urn:stale"), "1.0.0"),
                registrar,
                new EchoContractInvocableFactory(),
                ExecutionContext.empty()))
                .isInstanceOf(BrokerException.class)
                .extracting("reason")
                .isEqualTo(DispositionReason.BROKER_ACCEPT_INVALID);
        assertThat(registrar.registration("answer.search")).isEmpty();
    }

    @Test
    void invalidateEmitsEventAndRevokesRegisteredCapabilities() throws Exception {
        KeyPair keyPair = keyPair();
        InMemoryContractStore store = new InMemoryContractStore();
        store.put(Fixtures.frozenContract(keyPair));
        InMemoryCapabilityRegistrar registrar = new InMemoryCapabilityRegistrar();
        RecordingBrokerEventSink events = new RecordingBrokerEventSink();
        DefaultCompositionBroker broker = broker(store, keyPair, events);

        RegistrationHandle handle = broker.accept(
                broker.present(Fixtures.validProviderClaim(), ExecutionContext.empty()),
                registrar,
                new EchoContractInvocableFactory(),
                ExecutionContext.empty());

        broker.invalidate(handle, registrar, ExecutionContext.empty());

        assertThat(registrar.registration("answer.search")).isEmpty();
        assertThat(events.events()).extracting(BrokerEvent::type)
                .contains(BrokerEventType.CONTRACT_INVALIDATED, BrokerEventType.CAPABILITY_REVOKED);
    }

    private DefaultCompositionBroker broker(InMemoryContractStore store, KeyPair keyPair, RecordingBrokerEventSink events) {
        return new DefaultCompositionBroker(
                store,
                new OfflineContractVerifier(),
                VerificationKeySet.of(List.of(new VerificationKey("test-key", keyPair.getPublic()))),
                null,
                events);
    }

    private ExecutionContext context(String correlationId) {
        return new ExecutionContext("tenant", "user", List.of(), List.of(), correlationId, "request", Map.of(), Map.of());
    }

    private Claim malformedClaim() {
        Claim base = Fixtures.validProviderClaim();
        return new Claim(base.identity(), null, base.refusals(), base.dependencies(), base.offers(),
                base.conflictResolution(), base.negotiationSurface(), base.integrationPorts(), base.metadata());
    }

    private Claim claimWithDcpVersion(String dcpVersion) {
        Claim base = Fixtures.validProviderClaim();
        return new Claim(base.identity(), base.domain(), base.refusals(), base.dependencies(), base.offers(),
                base.conflictResolution(), base.negotiationSurface(), base.integrationPorts(),
                new ClaimMetadata(dcpVersion, base.identity().version(), Instant.EPOCH, Map.of()));
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
