package com.unfurl.dcp.description;

import com.unfurl.dcp.claim.*;

import java.util.List;

public record ComponentDescription(
        Identity identity,
        DomainAssertion domain,
        List<Refusal> refusals,
        Dependencies dependencies,
        List<Offer> offers,
        ConflictResolution conflictResolution,
        NegotiationSurface negotiationSurface,
        IntegrationPorts integrationPorts,
        ComponentMetadata metadata
) {
    public ComponentDescription {
        refusals = refusals == null ? List.of() : List.copyOf(refusals);
        offers = offers == null ? List.of() : List.copyOf(offers);
    }
}
