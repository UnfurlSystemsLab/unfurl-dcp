package com.unfurl.dcp.componentconfig;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when a packaged configuration file or a merge with deployment values fails. It carries every failure found,
 * not just the first, so one run lists every bad key (LLD, <em>Component configuration</em>).
 */
public final class ComponentConfigurationException extends RuntimeException {
    private final List<ConfigFailure> failures;

    /** @param failures every failure found; never empty */
    public ComponentConfigurationException(List<ConfigFailure> failures) {
        super(failures.stream().map(ConfigFailure::toString).collect(Collectors.joining("; ")));
        if (failures.isEmpty()) throw new IllegalArgumentException("at least one configuration failure is required");
        this.failures = List.copyOf(failures);
    }

    /** Returns every failure, in the order found (sorted by key). */
    public List<ConfigFailure> failures() {
        return failures;
    }
}
