package com.unfurl.dcp.documentation;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Value Object: one documented (active) capability of a component (DCP HLD-C2 G.2).
 *
 * <p>A host component lists both its own capabilities ({@link EntrySurface#HTTP}) and those it runs in process
 * ({@link EntrySurface#HOSTED_IN_PROCESS}). For a hosted one, {@code hostedBy} is the host and the provider fields name
 * the hosted component, whose own claim supplied the description, operations and faults.
 *
 * @param capability               the provider capability
 * @param capabilityVersion        the pinned capability version
 * @param description              the offer's description from the provider claim
 * @param contractId               the child contract that grants it
 * @param bindingId                its runtime binding; absent in a host view, which has no binding set
 * @param surface                  how it is reached
 * @param visibility               PUBLIC or INTERNAL (a PRIVATE capability is never documented)
 * @param path                     {@code /dcp/<capability>} for HTTP; absent for hosted
 * @param hostedBy                 the host component for hosted capabilities; absent for HTTP
 * @param providerComponentUri     the providing component
 * @param providerComponentVersion its pinned version
 * @param operations               the documented operations, sorted by name
 * @param faults                   fault codes whose {@code affects.offers} names this capability, sorted
 */
public record DocumentedCapability(String capability, String capabilityVersion, String description, URI contractId,
                                   URI bindingId, EntrySurface surface, Visibility visibility, String path, URI hostedBy,
                                   URI providerComponentUri, String providerComponentVersion,
                                   List<DocumentedOperation> operations, List<String> faults) {
    /** Validating constructor: required fields, non-empty operations, sorted copies. */
    public DocumentedCapability {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(providerComponentUri, "providerComponentUri");
        if (visibility == null || visibility == Visibility.PRIVATE) {
            throw new IllegalArgumentException("a documented capability is PUBLIC or INTERNAL");
        }
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("a documented capability has at least one operation");
        }
        operations = operations.stream().sorted(Comparator.comparing(DocumentedOperation::operation)).toList();
        faults = faults == null ? List.of() : faults.stream().sorted().distinct().toList();
    }
}
