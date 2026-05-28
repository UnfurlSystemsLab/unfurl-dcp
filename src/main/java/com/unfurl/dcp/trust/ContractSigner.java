package com.unfurl.dcp.trust;

public interface ContractSigner {
    SignedContract sign(byte[] canonicalContractBytes, SigningKeyRef keyRef);
}
