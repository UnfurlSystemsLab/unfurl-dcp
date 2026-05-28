package com.unfurl.dcp.trust;

public final class TrustTierDeriver {
    public TrustTier derive(TrustCreatedBy createdBy) {
        return createdBy == TrustCreatedBy.EMBEDDED_SELF ? TrustTier.SELF : TrustTier.NEUTRAL;
    }
}
