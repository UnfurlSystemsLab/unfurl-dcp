package com.unfurl.dcp.trust;

import jakarta.validation.constraints.NotBlank;

public record SignedContract(
        byte[] canonicalBytes,
        byte[] signature,
        @NotBlank String algorithm,
        @NotBlank String signerKeyId
) {
    public SignedContract {
        canonicalBytes = canonicalBytes == null ? new byte[0] : canonicalBytes.clone();
        signature = signature == null ? new byte[0] : signature.clone();
    }

    @Override
    public byte[] canonicalBytes() {
        return canonicalBytes.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }
}
