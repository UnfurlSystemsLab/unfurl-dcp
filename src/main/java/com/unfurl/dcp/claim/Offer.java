package com.unfurl.dcp.claim;

import jakarta.validation.constraints.NotBlank;

public record Offer(
        @NotBlank String capability,
        @NotBlank String description,
        ConsumerAccess consumerAccess,
        OfferInterface offerInterface,
        Stability stability,
        @NotBlank String version,
        boolean metered,
        String costImplications
) {
    public Offer {
        consumerAccess = consumerAccess == null ? ConsumerAccess.ANY : consumerAccess;
        stability = stability == null ? Stability.EVOLVING : stability;
    }
}
