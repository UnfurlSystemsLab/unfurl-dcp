package com.unfurl.dcp.runtimebinding;

import jakarta.validation.constraints.NotBlank;

public record SecretRef(@NotBlank String uri) {
}
