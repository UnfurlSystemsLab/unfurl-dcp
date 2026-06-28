package com.unfurl.dcp.claim;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Schema record: describes the design-time negotiation surface for intelligent components. The
 * endpoint/protocol metadata tells Fabric how to ask questions, while answer grounding and
 * limitations keep model-facing authoring bounded by explicit provider facts.
 */
public record NegotiationSurface(
        @NotBlank String endpoint,
        List<String> protocolsSupported,
        @NotEmpty List<SupportedIntent> supportedIntents,
        @JsonAlias("answer_grounding")
        @NotEmpty List<String> answerGrounding,
        @NotEmpty List<String> limitations
) {
    /**
     * Defensive-copy constructor: normalizes optional lists to empty immutable collections so claim
     * validation can reason over stable negotiation metadata.
     */
    public NegotiationSurface {
        protocolsSupported = protocolsSupported == null ? List.of() : List.copyOf(protocolsSupported);
        supportedIntents = supportedIntents == null ? List.of() : List.copyOf(supportedIntents);
        answerGrounding = answerGrounding == null ? List.of() : List.copyOf(answerGrounding);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
