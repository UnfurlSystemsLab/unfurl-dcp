package com.unfurl.dcp.runtimebinding;

import java.util.Map;

public record Configuration(Map<String, Object> values) {
    public Configuration {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
