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
     * Compact constructor: freezes repeated sections while preserving a missing
     * fault policy so claim projection cannot hide a malformed description.
     */
    public ComponentDescription {
        refusals = refusals == null ? List.of() : List.copyOf(refusals);
        offers = offers == null ? List.of() : List.copyOf(offers);
    }
}
