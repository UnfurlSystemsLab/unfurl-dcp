package com.unfurl.dcp.runtimebinding;

import jakarta.validation.constraints.NotBlank;

public record ConfigRef(@NotBlank String uri) {
}
