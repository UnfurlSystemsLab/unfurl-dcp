package com.unfurl.dcp.claim;

/**
 * Enumeration: how consumers reach an offer (DCP HLD-C2 A.6, {@code interface.kind}).
 *
 * <p>The kind is a fact about the offer, never about its deployment: where the provider runs is the runtime binding's
 * {@code deployment_kind} (HLD-C2 C).
 */
public enum InterfaceKind {
    /** An HTTP API. */
    HTTP_API,
    /** An event stream. */
    EVENT_STREAM,
    /** A negotiation surface; it states its {@code cost_implications}. */
    NEGOTIATION,
    /** Invoked in process, through its host. */
    IN_PROCESS,
    /**
     * Reached over a standard protocol of the provider's own, named in {@code details.protocol} (for example
     * {@code oauth2} for an identity provider's {@code identity-context}), never through the DCP invocation envelope. It
     * carries no {@code documentation}, and its documentation entry is always {@code external} (HLD-C2 G.1), whether
     * the provider is deployed with the composition or is one the customer already runs.
     */
    EXTERNAL_PROTOCOL
}
