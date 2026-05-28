package com.unfurl.dcp.description;

import java.util.Map;

public record ComponentMetadata(Map<String, Object> values) {
    public ComponentMetadata {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
