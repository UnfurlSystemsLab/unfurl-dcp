package com.unfurl.dcp.resolver;

import com.unfurl.dcp.claim.ConsumerAccess;

import java.net.URI;
import java.util.Set;

public record AccessPolicy(ConsumerAccess consumerAccess, Set<URI> namedConsumers) {
    public AccessPolicy {
        consumerAccess = consumerAccess == null ? ConsumerAccess.ANY : consumerAccess;
        namedConsumers = namedConsumers == null ? Set.of() : Set.copyOf(namedConsumers);
    }

    public boolean allows(URI consumerClaimUri) {
        return consumerAccess == ConsumerAccess.ANY || namedConsumers.contains(consumerClaimUri);
    }
}
