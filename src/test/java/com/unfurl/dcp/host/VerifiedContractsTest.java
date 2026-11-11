package com.unfurl.dcp.host;

import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.ContractFreezer;
import com.unfurl.dcp.contract.FrozenContract;
import com.unfurl.dcp.testing.CryptoFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage-0 tests for {@link VerifiedContracts} (LLD, <em>In-Process Component Host</em>): all-or-nothing verification,
 * signed-body equality, duplicate refusal, deterministic order, broker-store lookups and provider pins read only from
 * the verified body.
 */
class VerifiedContractsTest {
    private static final KeyPair KEYS = HostFixtures.KEYS;
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);

    @TempDir Path dir;

    /** Valid envelopes load, are ordered by id, and serve the broker's two exact lookups. */
    @Test
    void loadsOrdersAndIndexesVerifiedContracts() {
        VerifiedContracts contracts = VerifiedContracts.load(List.of(
                HostFixtures.source("z.json", HostFixtures.contract("urn:contract:z", "tool.z", Map.of())),
                HostFixtures.source("a.json", HostFixtures.contract("urn:contract:a", "tool.a", Map.of()))), CryptoFixtures.keySet(KEYS));

        assertThat(contracts.all()).extracting(VerifiedContract::id)
                .containsExactly("urn:contract:a@1.0.0", "urn:contract:z@1.0.0");
        assertThat(contracts.findByProvider(URI.create("urn:provider"), "1.0.0", "tool.a")).isPresent();
        assertThat(contracts.findByProvider(URI.create("urn:provider"), "1.0.0", "tool.b")).isEmpty();
        assertThat(contracts.findById(URI.create("urn:contract:z"), "1.0.0")).isPresent();
        assertThat(contracts.findById(URI.create("urn:contract:z"), "2.0.0")).isEmpty();
        assertThat(contracts.all().get(0).source()).isEqualTo("a.json");
    }

    /** A contract signed by another key fails the whole load, naming the envelope. */
    @Test
    void refusesAnUntrustedSignature() {
        KeyPair other = CryptoFixtures.keyPair();
        FrozenContract foreign = new ContractFreezer(CryptoFixtures.signingKeyRef())
                .freeze(HostFixtures.contract("urn:contract:a", "tool.a", Map.of()), CryptoFixtures.signer(other));
        assertUnverified(() -> VerifiedContracts.load(List.of(
                HostFixtures.source("ok.json", HostFixtures.contract("urn:contract:b", "tool.b", Map.of())),
                new ContractSource("foreign.json", foreign.canonicalBytes())), CryptoFixtures.keySet(KEYS)), "foreign.json");
    }

    /** An envelope whose body was edited after signing fails, though its signature still verifies. */
    @Test
    void refusesABodyThatDiffersFromItsSignedBytes() {
        byte[] envelope = HostFixtures.freeze(HostFixtures.contract("urn:contract:a", "tool.a", Map.of("artifactSha256", SHA_A))).canonicalBytes();
        String text = new String(envelope, StandardCharsets.UTF_8);
        int body = text.indexOf("\"contract\"");
        assertThat(body).isNotNegative();
        String tampered = text.substring(0, body) + text.substring(body).replaceFirst(SHA_A, SHA_B);
        assertThat(tampered).isNotEqualTo(text);

        assertUnverified(() -> VerifiedContracts.load(List.of(
                new ContractSource("tampered.json", tampered.getBytes(StandardCharsets.UTF_8))), CryptoFixtures.keySet(KEYS)),
                "tampered.json");
    }

    /** Duplicate identities or provider offers fail the load; undecodable bytes fail too. */
    @Test
    void refusesDuplicatesAndGarbage() {
        assertUnverified(() -> VerifiedContracts.load(List.of(
                HostFixtures.source("one.json", HostFixtures.contract("urn:contract:a", "tool.a", Map.of())),
                HostFixtures.source("two.json", HostFixtures.contract("urn:contract:b", "tool.a", Map.of()))), CryptoFixtures.keySet(KEYS)), "two.json");
        assertUnverified(() -> VerifiedContracts.load(List.of(
                new ContractSource("junk.json", "{".getBytes(StandardCharsets.UTF_8))), CryptoFixtures.keySet(KEYS)), "junk.json");
    }

    /** The directory form reads only top-level *.json files, in name order; a missing directory fails. */
    @Test
    void loadsADirectoryNonRecursively() throws Exception {
        Files.write(dir.resolve("b.json"), HostFixtures.freeze(HostFixtures.contract("urn:contract:b", "tool.b", Map.of())).canonicalBytes());
        Files.write(dir.resolve("a.json"), HostFixtures.freeze(HostFixtures.contract("urn:contract:a", "tool.a", Map.of())).canonicalBytes());
        Files.writeString(dir.resolve("notes.txt"), "ignored");
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested/c.json"), "{");

        assertThat(VerifiedContracts.loadDirectory(dir, CryptoFixtures.keySet(KEYS)).all()).hasSize(2);
        assertUnverified(() -> VerifiedContracts.loadDirectory(dir.resolve("absent"), CryptoFixtures.keySet(KEYS)), "absent");
    }

    /** The pin is read from the verified body: absent, complete with companions, or invalid only when used. */
    @Test
    void readsProviderPinsFromTheVerifiedBody() {
        VerifiedContracts contracts = VerifiedContracts.load(List.of(
                HostFixtures.source("none.json", HostFixtures.contract("urn:contract:none", "tool.none", Map.of())),
                HostFixtures.source("pinned.json", HostFixtures.contract("urn:contract:pinned", "tool.pinned", Map.of(
                        "artifactCoordinates", "com.example:component:1.0.0",
                        "artifactSha256", SHA_A,
                        "claimHash", SHA_B,
                        "companionArtifacts", List.of(Map.of("coordinates", "com.example:lib:2.0.0", "sha256", SHA_C))))),
                HostFixtures.source("partial.json", HostFixtures.contract("urn:contract:partial", "tool.partial", Map.of(
                        "artifactCoordinates", "com.example:component:1.0.0", "artifactSha256", SHA_A)))),
                CryptoFixtures.keySet(KEYS));

        assertThat(contract(contracts, "urn:contract:none").pin()).isEmpty();
        ProviderArtifactPin pin = contract(contracts, "urn:contract:pinned").pin().orElseThrow();
        assertThat(pin.claimArtifact()).isEqualTo(new ArtifactPin("com.example:component:1.0.0", SHA_A));
        assertThat(pin.claimHash()).isEqualTo(SHA_B);
        assertThat(pin.all()).extracting(ArtifactPin::coordinates)
                .containsExactly("com.example:component:1.0.0", "com.example:lib:2.0.0");
        assertThatThrownBy(() -> contract(contracts, "urn:contract:partial").pin())
                .isInstanceOfSatisfying(ComponentHostException.class, e -> {
                    assertThat(e.code()).isEqualTo(HostFailureCode.PROVIDER_PIN_INVALID);
                    assertThat(e.subject()).isEqualTo("urn:contract:partial@1.0.0");
                });
    }

    /**
     * Regression: a verified contract cannot be edited through any accessor. Nested extension and transport values
     * refuse modification, so the body served by the contract and by both store lookups still equals its signed bytes.
     */
    @Test
    @SuppressWarnings("unchecked")
    void verifiedContractsCannotBeMutatedThroughAccessors() throws Exception {
        VerifiedContracts contracts = VerifiedContracts.load(List.of(
                HostFixtures.source("pinned.json", HostFixtures.contract("urn:contract:pinned", "tool.pinned", Map.of(
                        "artifactCoordinates", "com.example:component:1.0.0",
                        "artifactSha256", SHA_A,
                        "claimHash", SHA_B,
                        "neededBy", List.of("urn:consumer"),
                        "companionArtifacts", List.of(Map.of("coordinates", "com.example:lib:2.0.0", "sha256", SHA_C)))))),
                CryptoFixtures.keySet(KEYS));
        VerifiedContract verified = contract(contracts, "urn:contract:pinned");

        List<CompositionContract> bodies = List.of(
                verified.contract(),
                verified.frozen().contract(),
                contracts.findById(URI.create("urn:contract:pinned"), "1.0.0").orElseThrow().contract(),
                contracts.findByProvider(URI.create("urn:provider"), "1.0.0", "tool.pinned").orElseThrow().contract());
        for (CompositionContract body : bodies) {
            Map<String, Object> extensions = body.metadata().extensions();
            List<Object> companions = (List<Object>) extensions.get("companionArtifacts");
            Map<String, Object> companion = (Map<String, Object>) companions.get(0);
            assertThatThrownBy(() -> companion.put("sha256", SHA_A)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> companions.add(Map.of())).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> ((List<Object>) extensions.get("neededBy")).set(0, "urn:other"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> extensions.put("claimHash", SHA_C)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> body.transport().details().put("endpoint", "x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        FrozenContract served = contracts.findById(URI.create("urn:contract:pinned"), "1.0.0").orElseThrow();
        assertThat(com.unfurl.dcp.contract.ContractCodec.canonicalMapper().writeValueAsBytes(served.contract()))
                .isEqualTo(served.signedContract().canonicalBytes());
        assertThat(verified.pin().orElseThrow().companions())
                .containsExactly(new ArtifactPin("com.example:lib:2.0.0", SHA_C));
        // Returned byte arrays are copies: editing one does not reach the stored contract.
        served.signedContract().canonicalBytes()[0] ^= 1;
        served.canonicalBytes()[0] ^= 1;
        assertThat(com.unfurl.dcp.contract.ContractCodec.canonicalMapper().writeValueAsBytes(verified.contract()))
                .isEqualTo(verified.frozen().signedContract().canonicalBytes());
    }

    /** Pin values reject malformed hashes, repeated coordinates and non-mapping companions. */
    @Test
    void pinValuesRejectMalformedInput() {
        assertThatThrownBy(() -> new ArtifactPin("g:a:1", "A".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtifactPin(" ", SHA_A)).isInstanceOf(IllegalArgumentException.class);
        ArtifactPin main = new ArtifactPin("g:a:1", SHA_A);
        assertThatThrownBy(() -> new ProviderArtifactPin(main, SHA_B, List.of(new ArtifactPin("g:a:1", SHA_C))))
                .hasMessageContaining("pinned twice");
        assertThatThrownBy(() -> ProviderArtifactPin.fromExtensions(Map.of("artifactCoordinates", "g:a:1",
                "artifactSha256", SHA_A, "claimHash", SHA_B, "companionArtifacts", List.of("g:b:1"))))
                .hasMessageContaining("{ coordinates, sha256 }");
        assertThatThrownBy(() -> ProviderArtifactPin.fromExtensions(Map.of("artifactSha256", SHA_A, "claimHash", SHA_B)))
                .hasMessageContaining("artifactCoordinates");
    }

    private static VerifiedContract contract(VerifiedContracts contracts, String id) {
        return contracts.verified(URI.create(id), "1.0.0").orElseThrow();
    }

    private static void assertUnverified(Runnable load, String subject) {
        assertThatThrownBy(load::run).isInstanceOfSatisfying(ComponentHostException.class, e -> {
            assertThat(e.code()).isEqualTo(HostFailureCode.CONTRACT_UNVERIFIED);
            assertThat(e.subject()).contains(subject);
        });
    }
}
