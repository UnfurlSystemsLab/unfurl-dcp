package com.unfurl.dcp.claim;

import java.util.List;

public record Dependencies(List<String> needs) {
    public Dependencies {
        needs = needs == null ? List.of() : List.copyOf(needs);
    }
}
