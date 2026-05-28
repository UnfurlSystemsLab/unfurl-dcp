package com.unfurl.dcp.validation;

import java.net.URI;
import java.util.Map;

public record Diagnostic(
        Severity severity,
        ErrorCode code,
        String message,
        URI claimUri,
        URI contractId,
        URI runtimeBindingId,
        String capabilityName,
        String fieldPath,
        String versionRange,
        String signerKeyId,
        String correlationId,
        Map<String, Object> metadata
) {
    public Diagnostic {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static Diagnostic error(ErrorCode code, String message, String fieldPath) {
        return new Diagnostic(Severity.ERROR, code, message, null, null, null, null, fieldPath, null, null, null, Map.of());
    }

    public static Diagnostic warning(ErrorCode code, String message, String fieldPath) {
        return new Diagnostic(Severity.WARNING, code, message, null, null, null, null, fieldPath, null, null, null, Map.of());
    }
}
