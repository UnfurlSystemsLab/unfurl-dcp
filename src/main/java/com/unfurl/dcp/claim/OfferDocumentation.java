package com.unfurl.dcp.claim;

import java.util.List;

/**
 * Value Object: an offer's documentation for generated API documentation (DCP HLD-C2 A.6), one entry per invocation
 * operation the offer accepts.
 *
 * <p>It is not part of the offer's matchable structure: {@code CapabilityResolver} and {@code OfferDetailMatcher} never
 * read it, so adding or changing it never changes which needs an offer satisfies. It is covered by the claim hash, so a
 * pinned claim also pins the schemas it names.
 *
 * @param summary    optional one-line summary of the capability
 * @param operations one entry per {@code invocation.operation} value; required non-empty when the offer is documented
 */
public record OfferDocumentation(String summary, List<OperationDocumentation> operations) {
    /** Defensive-copy constructor: an absent list is empty, so the validator reports it instead of a null check failing. */
    public OfferDocumentation {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
