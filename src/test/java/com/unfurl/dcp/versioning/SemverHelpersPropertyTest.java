package com.unfurl.dcp.versioning;

import com.vdurmont.semver4j.Semver;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

class SemverHelpersPropertyTest {
    @Property(tries = 50)
    void greaterThanOrEqualRangeMatchesSemverComparison(
            @ForAll @IntRange(min = 0, max = 5) int major,
            @ForAll @IntRange(min = 0, max = 10) int minor,
            @ForAll @IntRange(min = 0, max = 10) int patch,
            @ForAll @IntRange(min = 0, max = 5) int requiredMajor,
            @ForAll @IntRange(min = 0, max = 10) int requiredMinor,
            @ForAll @IntRange(min = 0, max = 10) int requiredPatch
    ) {
        String version = major + "." + minor + "." + patch;
        String required = requiredMajor + "." + requiredMinor + "." + requiredPatch;

        boolean expected = new Semver(version, Semver.SemverType.LOOSE)
                .compareTo(new Semver(required, Semver.SemverType.LOOSE)) >= 0;

        assertThat(new SemverHelpers().satisfies(version, ">=" + required)).isEqualTo(expected);
    }
}
