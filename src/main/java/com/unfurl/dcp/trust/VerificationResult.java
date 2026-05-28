package com.unfurl.dcp.trust;

public record VerificationResult(boolean valid, TrustTier derivedTier, String reason) {
    public static VerificationResult valid(TrustTier tier) {
        return new VerificationResult(true, tier, null);
    }

    public static VerificationResult invalid(String reason) {
        return new VerificationResult(false, null, reason);
    }
}
