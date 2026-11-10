package com.unfurl.dcp.host;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.ContractCodec;
import com.unfurl.dcp.contract.ContractLoader;
import com.unfurl.dcp.contract.ContractValidator;
import com.unfurl.dcp.contract.FrozenContract;
import com.unfurl.dcp.contract.LoadResult;
import com.unfurl.dcp.spi.ContractStore;
import com.unfurl.dcp.trust.OfflineContractVerifier;
import com.unfurl.dcp.trust.VerificationKeySet;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Repository: the verified frozen-contract set a host trusts, loaded all-or-nothing (LLD, <em>In-Process Component
 * Host</em>, stage 0). It is also the broker's {@link ContractStore}, so admission and broker lookup read the same
 * verified bytes. Generalizes Foundry's directory contract store, which delegates here.
 *
 * <p>Each envelope must decode, pass schema validation, verify against the trusted keys, and have a decoded body that
 * canonicalizes to exactly its signed bytes (the loader verifies the signature over the signed bytes only, so without
 * this check a tampered body could ride a valid signature). It must also name a provider and a binding, and no two
 * contracts may share a contract identity or a provider offer (claim URI, claim version, capability). The first failure
 * throws {@link HostFailureCode#CONTRACT_UNVERIFIED} naming the envelope; nothing is skipped.
 */
public final class VerifiedContracts implements ContractStore {
    private final List<VerifiedContract> all;
    private final Map<List<String>, VerifiedContract> byId;
    private final Map<List<String>, VerifiedContract> byProvider;

    private VerifiedContracts(List<VerifiedContract> all, Map<List<String>, VerifiedContract> byId,
                              Map<List<String>, VerifiedContract> byProvider) {
        this.all = all;
        this.byId = byId;
        this.byProvider = byProvider;
    }

    /**
     * Verifies and indexes a set of envelopes.
     *
     * @param sources the envelopes, in any order
     * @param keys    the host's trusted verification keys
     * @return the verified set, ordered by contract id then version
     * @throws ComponentHostException {@link HostFailureCode#CONTRACT_UNVERIFIED} on the first failing envelope
     */
    public static VerifiedContracts load(List<ContractSource> sources, VerificationKeySet keys) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(keys, "keys");
        ContractLoader loader = new ContractLoader(keys, new ContractValidator());
        OfflineContractVerifier verifier = new OfflineContractVerifier();
        List<VerifiedContract> verified = new ArrayList<>();
        Map<List<String>, VerifiedContract> ids = new HashMap<>();
        Map<List<String>, VerifiedContract> providers = new HashMap<>();
        for (ContractSource source : sources) {
            VerifiedContract contract = new VerifiedContract(source.name(), verify(source, loader, verifier));
            CompositionContract body = contract.contract();
            List<String> id = List.of(body.contractId().toString(), body.contractVersion());
            List<String> provider = List.of(body.parties().provider().claimUri().toString(),
                    body.parties().provider().claimVersion(), body.binding().providerCapability());
            if (ids.putIfAbsent(id, contract) != null || providers.putIfAbsent(provider, contract) != null) {
                throw unverified(source, "duplicate frozen contract identity or provider offer");
            }
            verified.add(contract);
        }
        verified.sort(Comparator.comparing((VerifiedContract c) -> c.contract().contractId().toString())
                .thenComparing(c -> c.contract().contractVersion()));
        return new VerifiedContracts(List.copyOf(verified), Map.copyOf(ids), Map.copyOf(providers));
    }

    /**
     * Verifies every {@code *.json} regular file directly in a directory (non-recursive), in file-name order.
     *
     * @throws ComponentHostException {@link HostFailureCode#CONTRACT_UNVERIFIED} when the directory is missing or
     *                                unreadable, or on the first failing envelope
     */
    public static VerifiedContracts loadDirectory(Path directory, VerificationKeySet keys) {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory)) {
            throw new ComponentHostException(HostFailureCode.CONTRACT_UNVERIFIED, directory.toString(),
                    "frozen contract directory is missing");
        }
        List<ContractSource> sources = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"))
                    .sorted().toList()) {
                sources.add(new ContractSource(file.getFileName().toString(), Files.readAllBytes(file)));
            }
        } catch (IOException failure) {
            throw new ComponentHostException(HostFailureCode.CONTRACT_UNVERIFIED, directory.toString(),
                    "frozen contract directory is unreadable", failure);
        }
        return load(sources, keys);
    }

    /** Returns every verified contract, ordered by contract id then version. */
    public List<VerifiedContract> all() {
        return all;
    }

    /** Returns the verified contract with this exact identity, if any. */
    public Optional<VerifiedContract> verified(URI contractId, String contractVersion) {
        if (contractId == null || contractVersion == null) return Optional.empty();
        return Optional.ofNullable(byId.get(List.of(contractId.toString(), contractVersion)));
    }

    /** Provider lookup: exact claim URI, claim version and capability; no latest or capability-name fallback. */
    @Override
    public Optional<FrozenContract> findByProvider(URI providerClaimUri, String providerClaimVersion, String providerCapability) {
        if (providerClaimUri == null || providerClaimVersion == null || providerCapability == null) return Optional.empty();
        return Optional.ofNullable(byProvider.get(List.of(providerClaimUri.toString(), providerClaimVersion, providerCapability)))
                .map(VerifiedContract::frozen);
    }

    /** Identity lookup: exact contract id and version only. */
    @Override
    public Optional<FrozenContract> findById(URI contractId, String contractVersion) {
        return verified(contractId, contractVersion).map(VerifiedContract::frozen);
    }

    /** Stage-0 check for one envelope: decode, schema, signature, signed-body equality, provider and binding present. */
    private static FrozenContract verify(ContractSource source, ContractLoader loader, OfflineContractVerifier verifier) {
        LoadResult result = loader.load(source.bytes(), verifier);
        if (result.frozenContract().isEmpty() || result.verificationResult() == null
                || !result.verificationResult().valid() || !result.validationReport().valid()) {
            throw unverified(source, "frozen contract is invalid or unsigned");
        }
        FrozenContract frozen = result.frozenContract().get();
        try {
            if (!Arrays.equals(ContractCodec.canonicalMapper().writeValueAsBytes(frozen.contract()),
                    frozen.signedContract().canonicalBytes())) {
                throw unverified(source, "frozen contract body does not match its signed bytes");
            }
        } catch (JsonProcessingException ex) {
            throw new ComponentHostException(HostFailureCode.CONTRACT_UNVERIFIED, source.name(),
                    "frozen contract body cannot be canonicalized", ex);
        }
        CompositionContract contract = frozen.contract();
        if (contract.parties() == null || contract.parties().provider() == null || contract.binding() == null) {
            throw unverified(source, "frozen contract lacks provider binding");
        }
        return frozen;
    }

    private static ComponentHostException unverified(ContractSource source, String detail) {
        return new ComponentHostException(HostFailureCode.CONTRACT_UNVERIFIED, source.name(), detail);
    }
}
