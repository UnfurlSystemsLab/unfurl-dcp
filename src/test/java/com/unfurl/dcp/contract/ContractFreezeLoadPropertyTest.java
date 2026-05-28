package com.unfurl.dcp.contract;

import com.unfurl.dcp.testing.CryptoFixtures;
import com.unfurl.dcp.testing.Fixtures;
import com.unfurl.dcp.trust.OfflineContractVerifier;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

class ContractFreezeLoadPropertyTest {
    @Property(tries = 10)
    void freezeLoadRefreezeIsByteStable(@ForAll @IntRange(min = 1, max = 10) int ignored) {
        KeyPair keyPair = CryptoFixtures.keyPair();
        ContractFreezer freezer = new ContractFreezer(CryptoFixtures.signingKeyRef());
        FrozenContract frozen = freezer.freeze(Fixtures.validContract(), CryptoFixtures.signer(keyPair));

        LoadResult loaded = new ContractLoader(CryptoFixtures.keySet(keyPair), new ContractValidator())
                .load(frozen.canonicalBytes(), new OfflineContractVerifier());
        FrozenContract refrozen = freezer.freeze(loaded.frozenContract().orElseThrow().contract(), CryptoFixtures.signer(keyPair));

        assertThat(loaded.loaded()).isTrue();
        assertThat(refrozen.canonicalBytes()).isEqualTo(frozen.canonicalBytes());
    }
}
