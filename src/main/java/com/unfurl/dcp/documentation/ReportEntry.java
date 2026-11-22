package com.unfurl.dcp.documentation;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * Value Object: one child contract as the documentation projection sees it (DCP HLD-C2 G.4). Every child has exactly one
 * entry, documented or not, so nothing is pruned silently.
 *
 * @param contractId           the child contract
 * @param bindingId            its runtime binding, when one exists
 * @param capability           the provider capability
 * @param providerComponentUri the providing component
 * @param surface              how it is reached
 * @param status               implied by {@code reason}
 * @param reason               why it has this status
 * @param detail               human-readable cause; never secrets
 * @param endpoint             the endpoint exactly as the binding declares it, for HTTP entries
 * @param observedAt           last observation of the entry's service root, when observed
 */
public record ReportEntry(URI contractId, URI bindingId, String capability, URI providerComponentUri,
                          EntrySurface surface, EntryStatus status, ReasonCode reason, String detail,
                          URI endpoint, Instant observedAt) {
    /** Validating constructor: required fields; the status is the one the reason implies (G.4). */
    public ReportEntry {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(providerComponentUri, "providerComponentUri");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(reason, "reason");
        if (reason == ReasonCode.SURFACE_NOT_SERVED) {
            throw new IllegalArgumentException("SURFACE_NOT_SERVED applies to API operations, not entries");
        }
        if (status != reason.status()) {
            throw new IllegalArgumentException("status " + status + " does not match reason " + reason);
        }
    }

    /** Factory: the status is derived from the reason. */
    public static ReportEntry of(URI contractId, URI bindingId, String capability, URI providerComponentUri,
                                 EntrySurface surface, ReasonCode reason, String detail, URI endpoint, Instant observedAt) {
        return new ReportEntry(contractId, bindingId, capability, providerComponentUri, surface, reason.status(), reason,
                detail, endpoint, observedAt);
    }
}
