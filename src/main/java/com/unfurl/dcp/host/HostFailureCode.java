package com.unfurl.dcp.host;

/**
 * Stable failure vocabulary of the in-process component host (LLD, <em>In-Process Component Host</em>, failure codes).
 * Every refusal names exactly one code, so hosts can surface it unchanged at startup and operators can act on it.
 * Grouped by the admission stage that raises it: 0 (contracts), 1 (data checks, no component code), 2 (activation).
 */
public enum HostFailureCode {
    /** Stage 0: an envelope is invalid, unsigned, signed by an untrusted key, duplicated, or its body differs from its signed bytes. */
    CONTRACT_UNVERIFIED,
    /** Stage 1: the verified contract has no provider artifact pin. */
    PROVIDER_PIN_MISSING,
    /** Stage 1: the verified contract's pin is malformed (partial, wrong type, or not a SHA-256), so it pins nothing. */
    PROVIDER_PIN_INVALID,
    /** Stage 1: no verified artifact carries the pinned provider's claim. */
    COMPONENT_ARTIFACT_MISSING,
    /** Stage 1/2: the packaged claim's (or activation's) identity differs from the contract's provider. */
    COMPONENT_CLAIM_MISMATCH,
    /** Stage 1: the packaged claim's canonical hash differs from the pinned {@code claimHash}. */
    CLAIM_HASH_MISMATCH,
    /** Stage 1: an artifact in the provider's pinned set is absent or its SHA-256 differs. */
    COMPONENT_DEPENDENCY_MISSING,
    /** Stage 1: a pinned artifact's manifest has {@code Class-Path} or {@code Launcher-Agent-Class}. */
    ARTIFACT_CLASS_PATH_FORBIDDEN,
    /** Stage 1: a pinned artifact contains a class or resource under a shared API prefix. */
    ARTIFACT_SHADOWS_SHARED_API,
    /** Stage 1: one class name appears in two artifacts of the same component. */
    ARTIFACT_CLASS_DUPLICATE,
    /** Stage 1: a packaged claim, configuration or activation declaration also appears under {@code META-INF/versions/}. */
    ARTIFACT_VERSIONED_METADATA,
    /** Stage 1: a packaged claim, packaged configuration or activation declaration is unreadable, over 1 MiB or malformed. */
    ARTIFACT_METADATA_INVALID,
    /** Stage 1: no activation declaration across the component's artifacts. */
    COMPONENT_ACTIVATION_MISSING,
    /** Stage 1: several activation declarations across the component's artifacts. */
    COMPONENT_ACTIVATION_DUPLICATE,
    /** Stage 1/2: the declared activation class is not in, or does not load from, the declaring artifact. */
    ACTIVATION_ORIGIN_MISMATCH,
    /** Stage 2: the declared class is not a public ComponentActivation with a public no-argument constructor, or constructing it threw. */
    COMPONENT_ACTIVATION_FAILED,
    /** Stage 2: a pinned class needs a type outside the pinned set and the shared API. */
    COMPONENT_DEPENDENCY_UNRESOLVED,
    /** Stage 1: the contract's consumer party, or its binding's consumer instance, is not this host. */
    CONSUMER_MISMATCH,
    /** Stage 1: a {@code source: deployment} configuration key has no value. */
    CONFIG_REQUIRED_MISSING,
    /** Stage 1: a configuration key is given both inline and as a reference. */
    CONFIG_SOURCE_AMBIGUOUS,
    /** Stage 1: a deployment configuration value names a key outside the schema. */
    CONFIG_KEY_UNKNOWN,
    /** Stage 1: a packaged key without {@code override} is overridden. */
    CONFIG_KEY_FIXED,
    /** Stage 1: a {@code tighten} override is looser than the packaged default. */
    CONFIG_OVERRIDE_WIDENS,
    /** Stage 1: a {@code secret-ref} value is not a {@code secret://} reference. */
    CONFIG_SECRET_INLINE,
    /** Stage 1: a configuration reference does not resolve to exactly {@code { value: … }}. */
    CONFIG_REF_UNRESOLVED,
    /** Stage 1: a configuration value breaks its type or bounds, or the packaged schema is invalid. */
    CONFIG_INVALID,
    /** Stage 2: {@code owner=host} needs not declared by the activation, or needing unbound port types. */
    HOST_DEPENDENCY_UNMET,
    /** Stage 1: the runtime binding fails validation against its contract, or is missing. */
    RUNTIME_BINDING_INVALID,
    /** Stage 1: the runtime binding is disabled. */
    RUNTIME_BINDING_DISABLED,
    /** Stage 2: the component offers no registrar of the host's shape. */
    REGISTRAR_SHAPE_UNSUPPORTED,
    /** Stage 1: the broker did not {@code ACCEPT} the provider's presentation for the contract. */
    ADMISSION_REJECTED
}
