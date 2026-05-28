package com.unfurl.dcp.claim;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.net.URI;

public record Identity(
        @NotNull URI uri,
        @NotBlank String name,
        @NotNull ComponentKind kind,
        @NotBlank String version,
        @NotBlank String publisher,
        URI publisherUri
) {
}
