package com.unfurl.dcp.documentation;

/**
 * Enumeration: why a documentation entry or a product API operation has the status it has (DCP HLD-C2 G.4). Every entry
 * the projection does not document carries one, so nothing is pruned silently.
 */
public enum ReasonCode {
    /** Documented. */
    ACTIVE(EntryStatus.ACTIVE),
    /** Visibility policy marks it private. */
    POLICY_PRIVATE(EntryStatus.INACTIVE),
    /** No runtime binding names the contract. */
    BINDING_MISSING(EntryStatus.INACTIVE),
    /** The binding is disabled. */
    BINDING_DISABLED(EntryStatus.INACTIVE),
    /** An HTTP entry with no endpoint, or an unresolvable endpoint reference. */
    BINDING_ENDPOINT_MISSING(EntryStatus.INACTIVE),
    /** The endpoint is not {@code <service_root>/dcp/<capability>}. */
    ENDPOINT_PATH_MISMATCH(EntryStatus.INACTIVE),
    /** A hosted entry whose consumer host has no service root in the set. */
    HOST_UNKNOWN(EntryStatus.INACTIVE),
    /** The registration report could not be obtained, or was not yet observed. */
    PROVIDER_UNREACHABLE(EntryStatus.INACTIVE),
    /** The host answered 401 or 403 to the observer. */
    REGISTRATION_UNAUTHORIZED(EntryStatus.INACTIVE),
    /** The report's own identity or claim hash differs from the host pinned for the root. */
    HOST_CLAIM_MISMATCH(EntryStatus.INACTIVE),
    /** The host check passed, but the registration's provider evidence differs from the entry's pin. */
    CLAIM_MISMATCH(EntryStatus.INACTIVE),
    /** The report does not list the contract. */
    NOT_REGISTERED(EntryStatus.INACTIVE),
    /** An external surface: listed, never probed. */
    EXTERNAL_SURFACE(EntryStatus.NOT_PROBED),
    /** A product API operation whose surface the host does not serve (API operations only). */
    SURFACE_NOT_SERVED(EntryStatus.INACTIVE);

    private final EntryStatus status;

    /** Constructor: binds each reason to the one status it implies. */
    ReasonCode(EntryStatus status) {
        this.status = status;
    }

    /** The status this reason implies, so a status and a reason can never disagree. */
    public EntryStatus status() {
        return status;
    }
}
