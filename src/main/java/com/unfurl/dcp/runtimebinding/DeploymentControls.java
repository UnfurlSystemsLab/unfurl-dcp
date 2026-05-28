package com.unfurl.dcp.runtimebinding;

import java.util.Map;

public record DeploymentControls(Map<String, Object> values) {
    public DeploymentControls {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
