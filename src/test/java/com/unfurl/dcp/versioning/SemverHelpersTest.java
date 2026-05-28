package com.unfurl.dcp.versioning;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemverHelpersTest {
    private final SemverHelpers semver = new SemverHelpers();

    @Test
    void supportsBasicComparisonRanges() {
        assertThat(semver.satisfies("1.2.0", ">=1.0.0")).isTrue();
        assertThat(semver.satisfies("0.9.9", ">=1.0.0")).isFalse();
    }

    @Test
    void choosesHighestSemverNotLexicographicString() {
        assertThat(semver.highestSatisfying(List.of("1.9.0", "1.10.0"), ">=1.0.0")).contains("1.10.0");
    }
}
