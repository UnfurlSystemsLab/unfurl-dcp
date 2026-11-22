package com.unfurl.dcp.documentation;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Value Object: one documentation gap (DCP HLD-C2 G.1): an entry that should be documented, but whose provider offer has
 * no documentation operations. A schema is never inferred for it.
 *
 * @param contractId       the child contract
 * @param capability       its capability
 * @param providerClaimUri the provider whose claim lacks the schemas
 */
public record DocumentationGap(URI contractId, String capability, URI providerClaimUri) {

    /** Failure: the projection refuses to produce anything while gaps exist, naming every one. */
    public static final class DocumentationGapException extends DocumentationException {
        private final List<DocumentationGap> gaps;

        /** Constructor: lists every gap in the message. */
        public DocumentationGapException(List<DocumentationGap> gaps) {
            super(DOCUMENTATION_GAP, gaps.size() + " documented capabilities have no documentation schemas: "
                    + gaps.stream().map(g -> g.capability() + " (" + g.providerClaimUri() + ", contract " + g.contractId() + ")")
                    .collect(Collectors.joining("; ")));
            this.gaps = List.copyOf(gaps);
        }

        /** Every gap found. */
        public List<DocumentationGap> gaps() {
            return gaps;
        }
    }
}
