package com.unfurl.dcp.runtimebinding;

import com.unfurl.dcp.value.DeepImmutable;
import java.util.Map;

public record DeploymentControls(Map<String, Object> values) {
    public DeploymentControls {
        // Deep copy: nested maps and lists are frozen too, so the record cannot change after it is built.
        values = DeepImmutable.map(values);
    }
}
