package com.unfurl.dcp.host;

import com.unfurl.dcp.componentconfig.ConfigFailureCode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the link between the two failure vocabularies: the host reports a configuration failure under the
 * {@link HostFailureCode} of the same name (LLD, <em>Component configuration</em>), so the two sets of {@code CONFIG_*}
 * names must stay identical.
 */
class HostFailureCodeTest {

    /** Every configuration failure code has a host code of the same name, and the host has no other CONFIG_ code. */
    @Test
    void configurationCodesMatchByName() {
        assertThat(Arrays.stream(HostFailureCode.values()).map(Enum::name).filter(name -> name.startsWith("CONFIG_")))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ConfigFailureCode.values()).map(Enum::name).toList());
    }
}
