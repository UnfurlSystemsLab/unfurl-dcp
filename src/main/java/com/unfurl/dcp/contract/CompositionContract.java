package com.unfurl.dcp.contract;

import java.net.URI;

/**
 * Schema record: DCP composition contract for one governed edge or aggregate contract node.
 *
 * <p>Pattern: Composite. A contract can be a leaf edge between one consumer and provider, or an
 * aggregate parent whose metadata contains child contract ids using the same containment bridge as
 * recursive DCP claims.
 */
public record CompositionContract(
        URI contractId,
        String contractVersion,
        Parties parties,
        Binding binding,
        DataMapping dataMapping,
        Transport transport,
        Expectations expectations,
        Provenance provenance,
        Trust trust,
        Invalidation invalidation,
        CompositionContractMetadata metadata
) {
}
