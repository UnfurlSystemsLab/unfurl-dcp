package com.unfurl.dcp.manifest;

import java.util.List;

public record Navigation(List<String> entries) {
    public Navigation {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
