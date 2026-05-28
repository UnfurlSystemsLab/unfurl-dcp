package com.unfurl.dcp.spi;

import com.unfurl.dcp.contract.FrozenContract;

import java.net.URI;
import java.util.Optional;

public interface ContractStore {
    Optional<FrozenContract> findByProvider(URI providerClaimUri, String providerClaimVersion);

    Optional<FrozenContract> findById(URI contractId, String contractVersion);
}
