package com.unfurl.dcp.claim;

import com.unfurl.dcp.testing.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Pins the canonical claim-hash rule (LLD, <em>In-Process Component Host</em>): composers freeze this value and hosts
 * recompute it, so it must be stable across runs and insensitive only to what canonicalization defines away.
 */
class ClaimHashTest {

    /**
     * Golden value: the canonical hash of the shared provider fixture. If this fails, the canonical bytes changed, which
     * invalidates every frozen provider pin; that is a breaking protocol change, not a test to update casually.
     */
    @Test
    void canonicalHashOfTheProviderFixtureIsStable() {
        assertThat(ClaimHash.canonical(Fixtures.validProviderClaim()))
                .isEqualTo(GOLDEN_PROVIDER_FIXTURE_HASH)
                .matches("[0-9a-f]{64}");
    }

    /** Equal claims hash equally; a semantic change (one more capability) changes the hash. */
    @Test
    void hashFollowsClaimContent() {
        Claim claim = Fixtures.validProviderClaim();
        assertThat(ClaimHash.canonical(claim)).isEqualTo(ClaimHash.canonical(Fixtures.validProviderClaim()));
        assertThat(ClaimHash.canonical(Fixtures.validProviderClaim("rag.search", "tool.call")))
                .isNotEqualTo(ClaimHash.canonical(claim));
    }

    /** An empty optional-dependency list is omitted, so adding the field did not change existing hashes. */
    @Test
    void emptyOptionalDependenciesDoNotChangeTheHash() {
        Claim claim = Fixtures.validProviderClaim();
        Claim same = withDependencies(claim, new Dependencies(claim.dependencies().needs(), List.of()));
        assertThat(ClaimHash.canonical(same)).isEqualTo(ClaimHash.canonical(claim));
    }

    /** A null claim is a programming error, not an empty hash. */
    @Test
    void refusesNull() {
        assertThatNullPointerException().isThrownBy(() -> ClaimHash.canonical(null));
    }

    private static final String GOLDEN_PROVIDER_FIXTURE_HASH = "1f8037076a7de23c178030009504ea0c8f8c83d9cf88ebcabe56b3f003e01244";

    private static Claim withDependencies(Claim claim, Dependencies dependencies) {
        return new Claim(claim.identity(), claim.domain(), claim.refusals(), dependencies, claim.offers(),
                claim.conflictResolution(), claim.negotiationSurface(), claim.integrationPorts(), claim.faults(),
                claim.metadata());
    }
}
