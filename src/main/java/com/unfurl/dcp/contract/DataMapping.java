package com.unfurl.dcp.contract;

import java.util.Map;

public record DataMapping(Map<String, String> inbound, Map<String, String> outbound) {
    public DataMapping {
        inbound = inbound == null ? Map.of() : Map.copyOf(inbound);
        outbound = outbound == null ? Map.of() : Map.copyOf(outbound);
    }
}
