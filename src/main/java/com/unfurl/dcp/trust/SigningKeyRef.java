package com.unfurl.dcp.trust;

import jakarta.validation.constraints.NotBlank;

public record SigningKeyRef(@NotBlank String keyId) {
}
