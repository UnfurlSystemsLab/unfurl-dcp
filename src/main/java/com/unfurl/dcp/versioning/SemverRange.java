package com.unfurl.dcp.versioning;

import jakarta.validation.constraints.NotBlank;

public record SemverRange(@NotBlank String expression) {
    public SemverRange {
        expression = expression == null || expression.isBlank() ? "*" : expression;
    }
}
