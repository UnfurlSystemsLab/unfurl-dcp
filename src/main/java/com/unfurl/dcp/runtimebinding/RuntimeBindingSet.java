package com.unfurl.dcp.runtimebinding;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Value Object: the {@code runtime_binding_set} document a composer writes for one deployment (HLD-C2 runtime
 * bindings): the bindings for a signed contract's edges, with the root binding's id when the set covers the root. An
 * overlay delivered to an in-process host holds only that host's bindings and has no root.
 *
 * <p>Invariants: every binding has an id and a contract id, and both are unique within the set, so a set maps each
 * contract to at most one binding.
 *
 * @param rootBindingId        the root binding's id, or null for a subset that excludes the root
 * @param generatedAt          when the composer generated the set; informational
 * @param sourceSignedContract the signed contract the set was generated from; informational
 * @param runtimeBindings      the bindings, in document order
 */
public record RuntimeBindingSet(URI rootBindingId, Instant generatedAt, String sourceSignedContract,
                                List<RuntimeBinding> runtimeBindings) {
    /** Validating constructor: copies the list and enforces unique binding and contract ids. */
    public RuntimeBindingSet {
        runtimeBindings = runtimeBindings == null ? List.of() : List.copyOf(runtimeBindings);
        Set<URI> ids = new HashSet<>();
        Set<URI> contracts = new HashSet<>();
        for (RuntimeBinding binding : runtimeBindings) {
            if (binding.bindingId() == null || !ids.add(binding.bindingId())) {
                throw new IllegalArgumentException("runtime binding id is missing or repeated: " + binding.bindingId());
            }
            if (binding.contractId() == null || !contracts.add(binding.contractId())) {
                throw new IllegalArgumentException("runtime binding contract id is missing or repeated: " + binding.contractId());
            }
        }
    }

    /** Returns the bindings keyed by the contract each one binds, in document order. */
    public Map<URI, RuntimeBinding> byContractId() {
        Map<URI, RuntimeBinding> byContract = new LinkedHashMap<>();
        runtimeBindings.forEach(binding -> byContract.put(binding.contractId(), binding));
        return Collections.unmodifiableMap(byContract);
    }
}
