package com.unfurl.dcp.trust;

import java.security.Signature;

public final class OfflineContractVerifier {
    public VerificationResult verify(SignedContract signed, VerificationKeySet keys) {
        if (signed == null) {
            return VerificationResult.invalid("signed contract is required");
        }
        if (keys == null) {
            return VerificationResult.invalid("verification keys are required");
        }
        return keys.find(signed.signerKeyId())
                .map(key -> verifyWithKey(signed, key))
                .orElseGet(() -> VerificationResult.invalid("verification key not found: " + signed.signerKeyId()));
    }

    private VerificationResult verifyWithKey(SignedContract signed, VerificationKey key) {
        try {
            Signature signature = Signature.getInstance(signed.algorithm());
            signature.initVerify(key.publicKey());
            signature.update(signed.canonicalBytes());
            return signature.verify(signed.signature())
                    ? VerificationResult.valid(TrustTier.NEUTRAL)
                    : VerificationResult.invalid("signature did not verify");
        } catch (Exception ex) {
            return VerificationResult.invalid(ex.getMessage());
        }
    }
}
