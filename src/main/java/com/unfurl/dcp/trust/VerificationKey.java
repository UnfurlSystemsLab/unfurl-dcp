package com.unfurl.dcp.trust;

import java.security.PublicKey;

public record VerificationKey(String keyId, PublicKey publicKey) {
}
