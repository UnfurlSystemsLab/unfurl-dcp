package com.unfurl.dcp.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.contract.Binding;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.CompositionContractMetadata;
import com.unfurl.dcp.contract.ContractFreezer;
import com.unfurl.dcp.contract.FrozenContract;
import com.unfurl.dcp.contract.Transport;
import com.unfurl.dcp.testing.CryptoFixtures;
import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.dcp.trust.VerificationKeySet;

import java.net.URI;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared fixtures for the in-process host tests: really signed contract envelopes (so they pass stage-0 verification),
 * pin extensions, and packaged manifests.
 */
final class HostFixtures {
    /** One signing key for the whole test run; generating RSA keys is slow. */
    static final KeyPair KEYS = CryptoFixtures.keyPair();

    private static final ObjectMapper MANIFEST_YAML = new ObjectMapper(new YAMLFactory())
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private HostFixtures() { }

    /** Returns the verification keys matching {@link #KEYS}. */
    static VerificationKeySet keys() {
        return CryptoFixtures.keySet(KEYS);
    }

    /** Builds an in-process, single-edge contract with its own id, provider capability and extensions. */
    static CompositionContract contract(String id, String capability, Map<String, Object> extensions) {
        return contract(id, capability, extensions, Fixtures.validContract().transport());
    }

    /** As {@link #contract(String, String, Map)}, with an explicit transport. */
    static CompositionContract contract(String id, String capability, Map<String, Object> extensions, Transport transport) {
        CompositionContract base = Fixtures.validContract();
        return new CompositionContract(URI.create(id), base.contractVersion(), base.parties(),
                new Binding(capability, capability, ">=1.0.0"), base.dataMapping(), transport, base.expectations(),
                base.provenance(), base.trust(), base.invalidation(), new CompositionContractMetadata(extensions));
    }

    /** Freezes and signs a contract with {@link #KEYS}. */
    static FrozenContract freeze(CompositionContract contract) {
        return new ContractFreezer(CryptoFixtures.signingKeyRef()).freeze(contract, CryptoFixtures.signer(KEYS));
    }

    /** Returns a signed envelope for a contract, named as its file would be. */
    static ContractSource source(String name, CompositionContract contract) {
        return new ContractSource(name, freeze(contract).canonicalBytes());
    }

    /** Builds the contract extensions that pin a provider's claim artifact, claim hash and companions. */
    static Map<String, Object> pinExtensions(ArtifactPin claimArtifact, String claimHash, ArtifactPin... companions) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("artifactCoordinates", claimArtifact.coordinates());
        extensions.put("artifactSha256", claimArtifact.sha256());
        extensions.put("claimHash", claimHash);
        if (companions.length > 0) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (ArtifactPin companion : companions) {
                list.add(Map.of("coordinates", companion.coordinates(), "sha256", companion.sha256()));
            }
            extensions.put("companionArtifacts", list);
        }
        return extensions;
    }

    /** Serializes a claim as the {@code META-INF/unfurl-catalog.yaml} a component packages. */
    static byte[] manifest(Claim claim) {
        try {
            return MANIFEST_YAML.writeValueAsBytes(Map.of("claim", claim, "catalog", Map.of()));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
