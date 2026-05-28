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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private DefaultCompositionBroker broker(InMemoryContractStore store, KeyPair keyPair, RecordingBrokerEventSink events) {
        return new DefaultCompositionBroker(
                store,
                new OfflineContractVerifier(),
                VerificationKeySet.of(List.of(new VerificationKey("test-key", keyPair.getPublic()))),
                null,
                events);
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
