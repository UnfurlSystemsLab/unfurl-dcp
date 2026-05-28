package com.unfurl.dcp.testing;

import com.unfurl.dcp.trust.ContractSigner;
import com.unfurl.dcp.trust.SignedContract;
import com.unfurl.dcp.trust.SigningKeyRef;
import com.unfurl.dcp.trust.VerificationKey;
import com.unfurl.dcp.trust.VerificationKeySet;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.List;

public final class CryptoFixtures {
    private CryptoFixtures() {
    }

    public static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static ContractSigner signer(KeyPair keyPair) {
        return (canonicalContractBytes, keyRef) -> sign(canonicalContractBytes, keyPair, keyRef.keyId());
    }

    public static SignedContract sign(byte[] canonicalBytes, KeyPair keyPair, String keyId) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(canonicalBytes);
            return new SignedContract(canonicalBytes, signature.sign(), "SHA256withRSA", keyId);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static VerificationKeySet keySet(KeyPair keyPair) {
        return VerificationKeySet.of(List.of(new VerificationKey("test-key", keyPair.getPublic())));
    }

    public static SigningKeyRef signingKeyRef() {
        return new SigningKeyRef("test-key");
    }
}
