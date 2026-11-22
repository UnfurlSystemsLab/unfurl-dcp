package com.unfurl.dcp.claim;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.unfurl.dcp.value.DeepImmutable;
import java.util.Map;

/**
 * Schema record: an offer's interface kind and its deterministic, matchable details (DCP HLD-C2 A.6).
 *
 * <p>The deployed wire name of {@code interfaceKind} is {@code interface_kind}; the specification's {@code kind} spelling
 * is accepted as an alias so a spec-shaped claim is never read with its interface silently dropped.
 */
public record OfferInterface(@JsonAlias("kind") InterfaceKind interfaceKind, Map<String, Object> details) {
    public OfferInterface {
        // Deep copy: nested maps and lists are frozen too, so the record cannot change after it is built.
        details = DeepImmutable.map(details);
    }
}
