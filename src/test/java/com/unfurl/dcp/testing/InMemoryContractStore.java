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

    public void put(FrozenContract contract) {
        byId.put(key(contract.contract().contractId(), contract.contract().contractVersion()), contract);
        byProvider.put(key(contract.contract().parties().provider().claimUri(), contract.contract().parties().provider().claimVersion()), contract);
    }

    @Override
    public Optional<FrozenContract> findByProvider(URI providerClaimUri, String providerClaimVersion) {
        return Optional.ofNullable(byProvider.get(key(providerClaimUri, providerClaimVersion)));
    }

    @Override
    public Optional<FrozenContract> findById(URI contractId, String contractVersion) {
        return Optional.ofNullable(byId.get(key(contractId, contractVersion)));
    }

    private String key(URI uri, String version) {
        return uri + "@" + version;
    }
}
