package com.unfurl.dcp.claim;

import jakarta.validation.constraints.NotBlank;

public record Refusal(@NotBlank String concern, @NotBlank String rationale, String ownedBy) {
}
