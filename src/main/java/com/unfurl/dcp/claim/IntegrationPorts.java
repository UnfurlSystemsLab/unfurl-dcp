package com.unfurl.dcp.claim;

import java.util.Map;

public record IntegrationPorts(Map<String, Object> ports) {
    public IntegrationPorts {
        ports = ports == null ? Map.of() : Map.copyOf(ports);
    }
}
