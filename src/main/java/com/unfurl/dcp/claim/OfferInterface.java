package com.unfurl.dcp.claim;

import com.unfurl.dcp.value.DeepImmutable;
import java.util.Map;

public record OfferInterface(InterfaceKind interfaceKind, Map<String, Object> details) {
    public OfferInterface {
        // Deep copy: nested maps and lists are frozen too, so the record cannot change after it is built.
        details = DeepImmutable.map(details);
    }
}
