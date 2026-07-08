package com.unfurl.dcp.description;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimMetadata;

import java.time.Instant;
import java.util.Map;

/**
 * Projector: converts the shared component self-description into the canonical
 * DCP claim projection consumed by validators, resolvers, and Fabric.
 */
public final class ClaimProjector {
    /**
     * Projection method: copies the description's fault policy into the claim so
     * runtime fault semantics stay attached to the component boundary.
     */
    public Claim toClaim(ComponentDescription description) {
        return new Claim(
                description.identity(),
                description.domain(),
                description.refusals(),
                description.dependencies(),
                description.offers(),
                description.conflictResolution(),
                description.negotiationSurface(),
                description.integrationPorts(),
                description.faults(),
                new ClaimMetadata("0.2.0", description.identity().version(), Instant.now(), Map.of()));
    }
}
