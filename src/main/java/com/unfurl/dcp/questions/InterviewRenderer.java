package com.unfurl.dcp.questions;

/**
 * Projector: renders the canonical negotiation question schema into the neutral human-interview
 * view consumed by Fabric. The normalized rows preserve id/type/target/prompt identity so the
 * interview and model-prompt projections can be compared without presentation chrome.
 */
public final class InterviewRenderer {
    /**
     * Project a question schema into interview rows. The context is accepted for future conditional
     * rendering, but v0.2 deliberately includes every canonical question so training and UI paths
     * see the same decision surface.
     */
    public InterviewView render(NegotiationQuestionSchema schema, NegotiationContext ctx) {
        return new InterviewView(schema.questions().stream()
                .filter(question -> applies(question, ctx))
                .map(this::normalize)
                .toList());
    }

    /**
     * Predicate hook: currently returns true for all questions because v0.2 has no conditional
     * exclusion semantics; keeping the hook makes future applies_when support local and symmetric.
     */
    private boolean applies(NegotiationQuestion question, NegotiationContext ctx) {
        return true;
    }

    /**
     * Normalization helper: encodes the semantic identity of a question in a stable string so view
     * equality tests compare protocol content rather than UI-specific formatting.
     */
    private String normalize(NegotiationQuestion question) {
        return question.id() + "|" + question.answerType() + "|" + question.feeds() + "|" + question.prompt();
    }
}
