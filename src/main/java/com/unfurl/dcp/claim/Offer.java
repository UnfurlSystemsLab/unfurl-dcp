package com.unfurl.dcp.claim;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

/**
 * Schema record: describes one provider capability offer in a DCP claim. Defaults preserve protocol
 * ergonomics by treating absent consumer access as public and absent stability as evolving, while
 * validators enforce version and cost implications where required.
 */
public record Offer(
        @NotBlank String capability,
        @NotBlank String description,
        @JsonAlias("consumer_access")
        ConsumerAccess consumerAccess,
        OfferInterface offerInterface,
        Stability stability,
        @NotBlank String version,
        boolean metered,
        String costImplications
) {
    /**
     * Defaulting constructor: normalizes omitted optional enum fields so resolver and validators can
     * operate deterministically without null-specific branches.
     */
    public Offer {
        consumerAccess = consumerAccess == null ? ConsumerAccess.ANY : consumerAccess;
        stability = stability == null ? Stability.EVOLVING : stability;
    }
}
