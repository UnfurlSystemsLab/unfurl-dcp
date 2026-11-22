package com.unfurl.dcp.documentation;

import java.net.URI;
import java.util.Comparator;
import java.util.List;

/**
 * Value Object: what a projection was derived from (DCP HLD-C2 G.2 {@code source}).
 *
 * @param rootContract           the verified aggregate root; absent in a host view, which has no composition files
 * @param contracts              every child contract id, sorted
 * @param runtimeBindings        every child binding id used, sorted
 * @param registeredCapabilities capability names the observed registration reports list, sorted and distinct
 */
public record DocumentationSource(URI rootContract, List<URI> contracts, List<URI> runtimeBindings,
                                  List<String> registeredCapabilities) {
    /** Defensive-copy constructor: sorted copies. */
    public DocumentationSource {
        contracts = sortedUris(contracts);
        runtimeBindings = sortedUris(runtimeBindings);
        registeredCapabilities = registeredCapabilities == null ? List.of()
                : registeredCapabilities.stream().sorted().distinct().toList();
    }

    /** Helper: a sorted, distinct copy. */
    private static List<URI> sortedUris(List<URI> uris) {
        return uris == null ? List.of() : uris.stream().distinct().sorted(Comparator.comparing(URI::toString)).toList();
    }
}
