package com.unfurl.dcp.spi;

import com.unfurl.dcp.contract.FrozenContract;

import java.net.URI;
import java.util.Optional;

/**
 * Port: lookup boundary for fabric-frozen DCP contracts. Runtime hosts own persistence, while the
 * broker depends only on deterministic reads keyed by provider claim identity, offered capability,
 * and contract id/version.
 */
public interface ContractStore {
    /**
     * Find the frozen child contract for one provider offer. The capability is part of the key so a
     * multi-offer provider such as Foundry cannot accidentally resolve {@code agent.run} to another
     * offer owned by the same claim.
     */
    Optional<FrozenContract> findByProvider(
            URI providerClaimUri,
            String providerClaimVersion,
            String providerCapability);

    /**
     * Find a frozen contract by its immutable contract id/version after a disposition has been
     * accepted or when a runtime binding directly pins the contract identity.
     */
    Optional<FrozenContract> findById(URI contractId, String contractVersion);
}
