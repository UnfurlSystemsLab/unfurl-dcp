package com.unfurl.dcp.trust;

import com.unfurl.dcp.testing.CryptoFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineContractVerifierTest {
    @Test
    void verifiesValidSignature() {
        KeyPair keyPair = CryptoFixtures.keyPair();
        SignedContract signed = CryptoFixtures.sign("hello".getBytes(StandardCharsets.UTF_8), keyPair, "test-key");

        assertThat(new OfflineContractVerifier().verify(signed, CryptoFixtures.keySet(keyPair)).valid()).isTrue();
    }

    @Test
    void rejectsTamperedBytes() {
        KeyPair keyPair = CryptoFixtures.keyPair();
        SignedContract signed = CryptoFixtures.sign("hello".getBytes(StandardCharsets.UTF_8), keyPair, "test-key");
        SignedContract tampered = new SignedContract("goodbye".getBytes(StandardCharsets.UTF_8), signed.signature(), signed.algorithm(), signed.signerKeyId());

        assertThat(new OfflineContractVerifier().verify(tampered, CryptoFixtures.keySet(keyPair)).valid()).isFalse();
    }

    @Test
    void rejectsWrongKey() {
        KeyPair keyPair = CryptoFixtures.keyPair();
        KeyPair wrongKey = CryptoFixtures.keyPair();
        SignedContract signed = CryptoFixtures.sign("hello".getBytes(StandardCharsets.UTF_8), keyPair, "test-key");

        assertThat(new OfflineContractVerifier().verify(signed, CryptoFixtures.keySet(wrongKey)).valid()).isFalse();
    }

    @Test
    void rejectsMissingKey() {
        SignedContract signed = CryptoFixtures.sign("hello".getBytes(StandardCharsets.UTF_8), CryptoFixtures.keyPair(), "missing");

        assertThat(new OfflineContractVerifier().verify(signed, VerificationKeySet.of(List.of())).valid()).isFalse();
    }
}
