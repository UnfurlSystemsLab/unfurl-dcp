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
     * Internal convenience constructor: keeps in-repo factories concise while
     * assigning the explicit empty DCP fault policy during active development.
     */
    public Claim(
            Identity identity,
            DomainAssertion domain,
            List<Refusal> refusals,
            Dependencies dependencies,
            List<Offer> offers,
            ConflictResolution conflictResolution,
            NegotiationSurface negotiationSurface,
            IntegrationPorts integrationPorts,
            ClaimMetadata metadata
    ) {
        this(identity, domain, refusals, dependencies, offers, conflictResolution,
                negotiationSurface, integrationPorts, FaultPolicy.empty(), metadata);
    }

    /**
     * Compact constructor: freezes collections and normalizes omitted fault
     * policy to an explicit empty policy for deterministic validation.
     */
    public Claim {
        refusals = refusals == null ? List.of() : List.copyOf(refusals);
        offers = offers == null ? List.of() : List.copyOf(offers);
        faults = faults == null ? FaultPolicy.empty() : faults;
    }
}
