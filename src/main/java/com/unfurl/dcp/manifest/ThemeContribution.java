package com.unfurl.dcp.manifest;

import java.util.Map;

public record ThemeContribution(ThemeMode mode, Map<String, String> tokens) {
    public ThemeContribution {
        tokens = tokens == null ? Map.of() : Map.copyOf(tokens);
    }
}
