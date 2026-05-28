package com.unfurl.dcp.validation;

import java.util.List;

public record SchemaValidationReport(List<Diagnostic> diagnostics) {
    public SchemaValidationReport {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static SchemaValidationReport ok() {
        return new SchemaValidationReport(List.of());
    }

    public static SchemaValidationReport of(Diagnostic diagnostic) {
        return new SchemaValidationReport(List.of(diagnostic));
    }

    public boolean valid() {
        return diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == Severity.ERROR);
    }
}
