package com.unfurl.dcp.runtimebinding;

public record RuntimePolicy(boolean enabled, Integer timeoutMs, String telemetryNamespace, boolean auditEnabled) {
}
