package com.unfurl.dcp.runtimebinding;

/**
 * Schema record: where a runtime binding is deployed (DCP HLD-C2 C, {@code target_environment}).
 *
 * <p>{@code name} is the environment (for example {@code dev} or {@code production}). The optional tenant, region and
 * namespace scope it further. Documentation projection filters by them (DCP Documentation §5). Absent values are not
 * written, so a binding without them serializes exactly as before.
 *
 * @param name      the environment name
 * @param tenant    optional tenant, realm or customer scope
 * @param region    optional deployment region
 * @param namespace optional runtime namespace
 */
public record TargetEnvironment(String name, String tenant, String region, String namespace) {
    /** Compatibility constructor: an environment without tenant, region or namespace. */
    public TargetEnvironment(String name) {
        this(name, null, null, null);
    }
}
