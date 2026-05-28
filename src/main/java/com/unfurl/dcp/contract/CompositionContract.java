package com.unfurl.dcp.contract;

import java.net.URI;

public record CompositionContract(
        URI contractId,
        String contractVersion,
        Parties parties,
        Binding binding,
        DataMapping dataMapping,
        Transport transport,
        Expectations expectations,
        Provenance provenance,
        Trust trust,
        Invalidation invalidation
) {
}
