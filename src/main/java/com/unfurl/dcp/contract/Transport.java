package com.unfurl.dcp.contract;

import java.util.Map;

public record Transport(TransportKind kind, Map<String, Object> details) {
    public Transport {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
