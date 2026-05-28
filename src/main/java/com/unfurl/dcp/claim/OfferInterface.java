package com.unfurl.dcp.claim;

import java.util.Map;

public record OfferInterface(InterfaceKind interfaceKind, Map<String, Object> details) {
    public OfferInterface {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
