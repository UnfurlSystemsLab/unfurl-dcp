package com.unfurl.dcp.claim;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record Claim(
        Identity identity,
        DomainAssertion domain,
        @NotEmpty List<Refusal> refusals,
        Dependencies dependencies,
        List<Offer> offers,
        ConflictResolution conflictResolution,
        NegotiationSurface negotiationSurface,
        IntegrationPorts integrationPorts,
        ClaimMetadata metadata
) {
    public Claim {
        refusals = refusals == null ? List.of() : List.copyOf(refusals);
        offers = offers == null ? List.of() : List.copyOf(offers);
    }
}
