package com.unfurl.dcp.versioning;

import com.vdurmont.semver4j.Semver;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SemverHelpers {
    private static final Pattern RANGE = Pattern.compile("^(>=|>|<=|<|=)?\\s*(.+)$");

    public boolean satisfies(String version, String range) {
        if (version == null || version.isBlank()) {
            return false;
        }
        String normalizedRange = range == null || range.isBlank() ? "*" : range;
        try {
            Semver semver = new Semver(version, Semver.SemverType.LOOSE);
            if ("*".equals(normalizedRange)) {
                return true;
            }
            Matcher matcher = RANGE.matcher(normalizedRange);
            if (!matcher.matches()) {
                return semver.satisfies(normalizedRange);
            }
            String operator = matcher.group(1) == null ? "=" : matcher.group(1);
            Semver required = new Semver(matcher.group(2), Semver.SemverType.LOOSE);
            int comparison = semver.compareTo(required);
            return switch (operator) {
                case ">=" -> comparison >= 0;
                case ">" -> comparison > 0;
                case "<=" -> comparison <= 0;
                case "<" -> comparison < 0;
                case "=" -> comparison == 0;
                default -> false;
            };
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public Optional<String> highestSatisfying(Collection<String> versions, String range) {
        if (versions == null) {
            return Optional.empty();
        }
        return versions.stream()
                .filter(version -> satisfies(version, range))
                .max(Comparator.comparing(version -> new Semver(version, Semver.SemverType.LOOSE)));
    }
}
