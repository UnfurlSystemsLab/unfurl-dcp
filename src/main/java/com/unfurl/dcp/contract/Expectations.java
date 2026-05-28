package com.unfurl.dcp.contract;

public record Expectations(Integer timeoutMs, boolean idempotent, boolean async, boolean correlationIdRequired) {
}
