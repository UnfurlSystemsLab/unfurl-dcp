package com.unfurl.dcp.host;

import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.ContractFreezer;
import com.unfurl.dcp.contract.FrozenContract;
import com.unfurl.dcp.testing.CryptoFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link VerifiedComposition}: the children must be exactly the signed root's closure, so a deleted child (such
 * as an optional component's contract) or an added one fails the load, and every envelope, the root included, must verify.
 */
class VerifiedCompositionTest {
    private static final CompositionContract AGENT = HostFixtures.contract("urn:contract:agent", "agent.run", Map.of());
    private static final CompositionContract IDENTITY = HostFixtures.contract("urn:contract:identity", "identity-context", Map.of());

    @TempDir Path dir;

    /** A complete composition loads, and its children are found by capability. */
    @Test
    void loadsTheRootAndExactlyItsChildren() {
        VerifiedComposition composition = VerifiedComposition.load(root(AGENT, IDENTITY),
                List.of(HostFixtures.source("agent.json", AGENT), HostFixtures.source("identity.json", IDENTITY)), HostFixtures.keys());

        assertThat(composition.root().contract().contractId().toString()).isEqualTo("urn:contract:root");
        assertThat(composition.childrenFor("identity-context")).extracting(VerifiedContract::id)
                .containsExactly("urn:contract:identity@1.0.0");
        assertThat(composition.childrenFor("agent.run")).hasSize(1);
    }

    /** Deleting an optional component's contract does not remove the component: the load fails, naming it. */
    @Test
    void refusesAMissingChild() {
        assertUnverified(() -> VerifiedComposition.load(root(AGENT, IDENTITY), List.of(HostFixtures.source("agent.json", AGENT)),
                HostFixtures.keys()), "missing: urn:contract:identity");
    }

    /** A contract the root does not name is refused. */
    @Test
    void refusesAnExtraChild() {
        assertUnverified(() -> VerifiedComposition.load(root(AGENT),
                List.of(HostFixtures.source("agent.json", AGENT), HostFixtures.source("identity.json", IDENTITY)),
                HostFixtures.keys()), "not part of the signed composition: urn:contract:identity");
    }

    /** The root must verify too: one signed by an untrusted key fails the load. */
    @Test
    void refusesAnUntrustedRoot() {
        KeyPair other = CryptoFixtures.keyPair();
        FrozenContract foreign = new ContractFreezer(CryptoFixtures.signingKeyRef()).freeze(rootContract(AGENT), CryptoFixtures.signer(other));
        assertUnverified(() -> VerifiedComposition.load(new ContractSource("root.json", foreign.canonicalBytes()),
                List.of(HostFixtures.source("agent.json", AGENT)), HostFixtures.keys()), "root.json");
    }

    /** A child whose signature was corrupted fails the load before anything is read from it. */
    @Test
    void refusesACorruptedChild() {
        byte[] bytes = HostFixtures.freeze(IDENTITY).canonicalBytes();
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        int signature = text.indexOf("\"signature\"");
        assertThat(signature).isPositive();
        int value = text.indexOf('"', text.indexOf(':', signature) + 1) + 1;
        char flipped = text.charAt(value) == 'A' ? 'B' : 'A';
        String corrupted = text.substring(0, value) + flipped + text.substring(value + 1);
        assertUnverified(() -> VerifiedComposition.load(root(AGENT, IDENTITY), List.of(HostFixtures.source("agent.json", AGENT),
                new ContractSource("identity.json", corrupted.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                HostFixtures.keys()), "identity.json");
    }

    /** The directory form reads the root file and the child directory; a missing root is refused. */
    @Test
    void loadsFromDirectories() throws Exception {
        Path children = Files.createDirectories(dir.resolve("frozen"));
        Files.write(children.resolve("agent.json"), HostFixtures.freeze(AGENT).canonicalBytes());
        Files.write(dir.resolve("root.frozen.json"), root(AGENT).bytes());

        assertThat(VerifiedComposition.loadDirectory(dir.resolve("root.frozen.json"), children, HostFixtures.keys())
                .children().all()).hasSize(1);
        assertUnverified(() -> VerifiedComposition.loadDirectory(dir.resolve("absent.json"), children, HostFixtures.keys()),
                "root is missing");
    }

    private static ContractSource root(CompositionContract... children) {
        return HostFixtures.source("root.frozen.json", rootContract(children));
    }

    private static CompositionContract rootContract(CompositionContract... children) {
        List<Map<String, String>> contains = java.util.Arrays.stream(children)
                .map(child -> Map.of("contractId", child.contractId().toString())).toList();
        return HostFixtures.contract("urn:contract:root", "assembly.aggregate", Map.of("contains", contains));
    }

    private static void assertUnverified(org.assertj.core.api.ThrowableAssert.ThrowingCallable load, String message) {
        assertThatThrownBy(load).isInstanceOf(ComponentHostException.class)
                .satisfies(failure -> assertThat(((ComponentHostException) failure).code()).isEqualTo(HostFailureCode.CONTRACT_UNVERIFIED))
                .satisfies(failure -> assertThat(failure.getMessage() + " " + ((ComponentHostException) failure).subject())
                        .contains(message));
    }
}
