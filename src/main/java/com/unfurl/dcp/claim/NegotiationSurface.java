package com.unfurl.dcp.claim;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record NegotiationSurface(
        @NotBlank String endpoint,
        List<String> protocolsSupported,
        @NotEmpty List<SupportedIntent> supportedIntents,
        @NotEmpty List<String> answerGrounding,
        @NotEmpty List<String> limitations
) {
    public NegotiationSurface {
        protocolsSupported = protocolsSupported == null ? List.of() : List.copyOf(protocolsSupported);
        supportedIntents = supportedIntents == null ? List.of() : List.copyOf(supportedIntents);
        answerGrounding = answerGrounding == null ? List.of() : List.copyOf(answerGrounding);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
