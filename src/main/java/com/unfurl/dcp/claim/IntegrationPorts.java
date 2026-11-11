package com.unfurl.dcp.claim;

import com.unfurl.dcp.value.DeepImmutable;
import java.util.Map;

public record IntegrationPorts(Map<String, Object> ports) {
    public IntegrationPorts {
        // Deep copy: nested maps and lists are frozen too, so the record cannot change after it is built.
        ports = DeepImmutable.map(ports);
    }
}
