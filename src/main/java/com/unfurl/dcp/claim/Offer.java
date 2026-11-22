package com.unfurl.dcp.claim;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

/**
 * Schema record: describes one provider capability offer in a DCP claim. Defaults preserve protocol
 * ergonomics by treating absent consumer access as public and absent stability as evolving, while
 * validators enforce version and cost implications where required.
 *
 * <p>{@code documentation} (DCP HLD-C2 A.6) carries the per-operation payload schemas for generated documentation. It
 * is never matched by the resolver. It is {@code null} when absent and then omitted from the canonical claim hash, so a
 * claim without it keeps its pre-existing hash.
 *
 * <p>The wire name of {@code offerInterface} is {@code offer_interface}; the specification's {@code interface} spelling
 * is accepted as an alias while the two are reconciled (DCP LLD, <em>Claim</em>, wire-name drift).
 */
public record Offer(
        @NotBlank String capability,
        @NotBlank String description,
        @JsonAlias("consumer_access")
        ConsumerAccess consumerAccess,
        @JsonAlias("interface")
        OfferInterface offerInterface,
        Stability stability,
        @NotBlank String version,
        boolean metered,
        String costImplications,
        OfferDocumentation documentation
) {
    /**
     * Defaulting constructor: normalizes omitted optional enum fields so resolver and validators can
     * operate deterministically without null-specific branches.
     */
    public Offer {
        consumerAccess = consumerAccess == null ? ConsumerAccess.ANY : consumerAccess;
        stability = stability == null ? Stability.EVOLVING : stability;
    }

    /**
     * Compatibility constructor: an offer without documentation, so every producer written before HLD-C2 A.6 compiles
     * unchanged.
     */
    public Offer(String capability, String description, ConsumerAccess consumerAccess, OfferInterface offerInterface,
                 Stability stability, String version, boolean metered, String costImplications) {
        this(capability, description, consumerAccess, offerInterface, stability, version, metered, costImplications, null);
    }
}
