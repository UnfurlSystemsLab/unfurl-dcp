package com.unfurl.dcp.contract;

import com.unfurl.dcp.trust.ContractSigner;
import com.unfurl.dcp.trust.SignedContract;
import com.unfurl.dcp.trust.SigningKeyRef;

public final class ContractFreezer {
    private final SigningKeyRef signingKeyRef;

    public ContractFreezer(SigningKeyRef signingKeyRef) {
        this.signingKeyRef = signingKeyRef;
    }

    public FrozenContract freeze(CompositionContract contract, ContractSigner signer) {
        try {
            byte[] canonical = ContractCodec.canonicalMapper().writeValueAsBytes(contract);
            SignedContract signed = signer.sign(canonical, signingKeyRef);
            return new FrozenContract(canonical, contract, signed);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to freeze contract", ex);
        }
    }
}
