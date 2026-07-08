package com.unfurl.dcp.description;

import com.unfurl.dcp.claim.*;
import com.unfurl.dcp.fault.FaultPolicy;

import java.util.List;

/**
 * Source model: shared component self-description projected into DCP claim and
 * webapp manifest views so product facets do not drift.
 */
public record ComponentDescription(
        Identity identity,
        DomainAssertion domain,
        List<Refusal> refusals,
        Dependencies dependencies,
        List<Offer> offers,
        ConflictResolution conflictResolution,
        NegotiationSurface negotiationSurface,
        IntegrationPorts integrationPorts,
        FaultPolicy faults,
        ComponentMetadata metadata
) {
    /**
     * Internal convenience constructor: supplies an explicit empty fault policy
     * for component descriptions that have not declared runtime faults yet.
     */
    public ComponentDescription(
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
        this(identity, domain, refusals, dependencies, offers, conflictResolution,
                negotiationSurface, integrationPorts, FaultPolicy.empty(), metadata);
    }

    /**
     * Compact constructor: freezes repeated sections and normalizes omitted
     * fault declarations to a deterministic empty policy.
     */
    public ComponentDescription {
        refusals = refusals == null ? List.of() : List.copyOf(refusals);
        offers = offers == null ? List.of() : List.copyOf(offers);
        faults = faults == null ? FaultPolicy.empty() : faults;
    }
}
