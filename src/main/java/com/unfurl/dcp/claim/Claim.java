package com.unfurl.dcp.claim;

import com.unfurl.dcp.fault.FaultPolicy;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Schema record: canonical DCP claim, including declared domain boundaries,
 * offers, integration ports, and first-class fault vocabulary.
 */
public record Claim(
        Identity identity,
        DomainAssertion domain,
        @NotEmpty List<Refusal> refusals,
        Dependencies dependencies,
        List<Offer> offers,
        ConflictResolution conflictResolution,
        NegotiationSurface negotiationSurface,
        IntegrationPorts integrationPorts,
        FaultPolicy faults,
        ClaimMetadata metadata
) {
    /**
     * Compact constructor: freezes repeated sections while preserving a missing
     * fault policy so ClaimValidator can report the required-section diagnostic.
     */
    public Claim {
        refusals = refusals == null ? List.of() : List.copyOf(refusals);
        offers = offers == null ? List.of() : List.copyOf(offers);
    }
}
