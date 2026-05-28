package com.unfurl.dcp.description;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimMetadata;

import java.time.Instant;
import java.util.Map;

public final class ClaimProjector {
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
                new ClaimMetadata("0.2.0", description.identity().version(), Instant.now(), Map.of()));
    }
}
