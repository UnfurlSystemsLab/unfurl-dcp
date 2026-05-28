package com.unfurl.dcp.questions;

import com.unfurl.dcp.claim.Claim;

import java.util.List;

public record NegotiationContext(Claim consumerClaim, Claim providerClaim, List<CapturedAnswer> priorAnswers) {
    public NegotiationContext {
        priorAnswers = priorAnswers == null ? List.of() : List.copyOf(priorAnswers);
    }
}
