package com.unfurl.dcp.componentconfig;

/**
 * Stable failure vocabulary of component configuration (LLD, <em>Component configuration</em>, merge and checks). An
 * in-process host reports each under its {@code HostFailureCode} of the same name; a composer reports them at scan and
 * when it writes runtime bindings.
 */
public enum ConfigFailureCode {
    /** A {@code source: deployment} key has no value. */
    CONFIG_REQUIRED_MISSING,
    /** A key is given both inline and as a reference. */
    CONFIG_SOURCE_AMBIGUOUS,
    /** A deployment value names a key outside the schema. */
    CONFIG_KEY_UNKNOWN,
    /** A packaged key without {@code override} is overridden. */
    CONFIG_KEY_FIXED,
    /** A {@code tighten} override is looser than the packaged default. */
    CONFIG_OVERRIDE_WIDENS,
    /** A {@code secret-ref} value is not a {@code secret://} reference. */
    CONFIG_SECRET_INLINE,
    /** A reference is malformed, cannot be resolved, or does not resolve to exactly {@code { value: … }}. */
    CONFIG_REF_UNRESOLVED,
    /** A value breaks its type or bounds, or the packaged schema itself is invalid. */
    CONFIG_INVALID
}
