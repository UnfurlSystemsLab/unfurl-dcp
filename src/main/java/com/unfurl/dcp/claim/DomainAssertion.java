package com.unfurl.dcp.claim;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record DomainAssertion(
        @NotBlank String summary,
        @NotEmpty List<Concern> concerns,
        @NotEmpty List<String> boundaryPrinciples
) {
    public DomainAssertion {
        concerns = concerns == null ? List.of() : List.copyOf(concerns);
        boundaryPrinciples = boundaryPrinciples == null ? List.of() : List.copyOf(boundaryPrinciples);
    }
}
