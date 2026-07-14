package com.unfurl.dcp.testing;

import com.unfurl.dcp.contract.FrozenContract;
import com.unfurl.dcp.spi.ContractStore;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryContractStore implements ContractStore {
    private final Map<String, FrozenContract> byId = new LinkedHashMap<>();
    private final Map<String, FrozenContract> byProvider = new LinkedHashMap<>();

    /**
     * Store helper: indexes contracts by id and provider/capability so tests mirror the production
     * broker lookup semantics for multi-offer providers.
     */
    public void put(FrozenContract contract) {
        byId.put(key(contract.contract().contractId(), contract.contract().contractVersion()), contract);
        byProvider.put(providerKey(
                contract.contract().parties().provider().claimUri(),
                contract.contract().parties().provider().claimVersion(),
                contract.contract().binding().providerCapability()), contract);
    }

    @Override
    public Optional<FrozenContract> findByProvider(URI providerClaimUri, String providerClaimVersion, String providerCapability) {
        return Optional.ofNullable(byProvider.get(providerKey(providerClaimUri, providerClaimVersion, providerCapability)));
    }

    @Override
    public Optional<FrozenContract> findById(URI contractId, String contractVersion) {
        return Optional.ofNullable(byId.get(key(contractId, contractVersion)));
    }

    private String key(URI uri, String version) {
        return uri + "@" + version;
    }

    /**
     * Compound-key builder: includes capability to avoid overwriting sibling offers from the same
     * provider claim.
     */
    private String providerKey(URI uri, String version, String capability) {
        return key(uri, version) + "#" + capability;
    }
}
