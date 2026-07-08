package com.unfurl.dcp.fault;

import com.unfurl.dcp.claim.Claim;

import java.util.List;

/**
 * Strategy: deterministically evaluates runtime fault signals against a
 * claim's declared DCP fault vocabulary without model calls or renegotiation.
 */
public final class FaultPropagationGate {
    /**
     * Evaluate one observed fault against the source claim's declared fault
     * policy, returning propagate/suppress/reject semantics for parent graphs.
     */
    public FaultPropagationDecision evaluate(Claim sourceClaim, FaultSignal signal) {
        if (sourceClaim == null || sourceClaim.faults() == null || signal == null || signal.code().isBlank()) {
            return suppressed("fault signal or source claim is missing declared fault context");
        }
        FaultDeclaration declaration = sourceClaim.faults().emitted().stream()
                .filter(candidate -> signal.code().equals(candidate.code()))
                .findFirst()
                .orElse(null);
        if (declaration == null) {
            return suppressed("fault code is not declared by the source claim");
        }
        ParentImpact impact = declaration.propagation().parentImpact();
        List<String> needs = signal.affectedNeeds().isEmpty() ? declaration.affects().needs() : signal.affectedNeeds();
        List<String> offers = signal.affectedOffers().isEmpty() ? declaration.affects().offers() : signal.affectedOffers();
        List<String> constraints = signal.affectedConstraints().isEmpty() ? declaration.affects().constraints() : signal.affectedConstraints();
        if (impact == ParentImpact.NONE) {
            return new FaultPropagationDecision(false, ParentImpact.NONE, "declared fault is suppressed at parent boundary", needs, offers, constraints);
        }
        return new FaultPropagationDecision(true, impact, "declared fault propagates through DCP parent-impact gate", needs, offers, constraints);
    }

    /**
     * Factory helper: builds a non-propagating decision for malformed or
     * undeclared fault context.
     */
    private FaultPropagationDecision suppressed(String reason) {
        return new FaultPropagationDecision(false, ParentImpact.NONE, reason, List.of(), List.of(), List.of());
    }
}
