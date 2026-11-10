package com.unfurl.dcp.claim;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code owner=} dependency qualifier rule shared by composers and in-process hosts (LLD, <em>In-Process
 * Component Host</em>): which owner a dependency names, and which owners bind outside the peer-component graph.
 */
class DependenciesOwnerTest {

    /** The first owner parameter is returned trimmed; other qualifiers and versions are ignored. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "host.audit@v1?owner=host|host",
            "host.audit@v1?scope=read&owner=host|host",
            "host.audit@v1?owner= fabric &owner=host|fabric",
            "kms.sign@^1?owner=customer-acme|customer-acme",
            "agent.run@^0.1|''",
            "agent.run@^0.1?|''",
            "agent.run@^0.1?scope=read|''",
            "agent.run@^0.1?ownerx=host|''"
    })
    void ownerOfReadsTheOwnerQualifier(String dependency, String owner) {
        assertThat(Dependencies.ownerOf(dependency)).isEqualTo(owner);
    }

    /** Blank or missing dependency text has no owner. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void blankDependencyHasNoOwner(String dependency) {
        assertThat(Dependencies.ownerOf(dependency)).isEmpty();
        assertThat(Dependencies.isExternallyOwned(dependency)).isFalse();
    }

    /** Host, Fabric and customer owners are external; no owner or any other owner is a peer dependency. */
    @ParameterizedTest
    @CsvSource({
            "host.audit@v1?owner=host,true",
            "deploy.target@v1?owner=fabric,true",
            "kms.sign@v1?owner=customer-controlled,true",
            "kms.sign@v1?owner=customer-acme,true",
            "agent.run@^0.1,false",
            "agent.run@^0.1?owner=peer,false",
            "agent.run@^0.1?owner=hosted,false"
    })
    void externalOwnersAreHostFabricAndCustomer(String dependency, boolean external) {
        assertThat(Dependencies.isExternallyOwned(dependency)).isEqualTo(external);
    }
}
