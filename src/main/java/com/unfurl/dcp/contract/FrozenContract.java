package com.unfurl.dcp.contract;

import com.unfurl.dcp.trust.SignedContract;

public record FrozenContract(byte[] canonicalBytes, CompositionContract contract, SignedContract signedContract) {
    public FrozenContract {
        canonicalBytes = canonicalBytes == null ? new byte[0] : canonicalBytes.clone();
    }

    @Override
    public byte[] canonicalBytes() {
        return canonicalBytes.clone();
    }
}
