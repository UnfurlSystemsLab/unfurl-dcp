package com.unfurl.dcp.questions;

/**
 * Projector: renders the canonical negotiation question schema into the neutral model-prompt view.
 * It intentionally mirrors InterviewRenderer's normalized content so human and model authoring paths
 * stay aligned while Fabric owns the actual model execution outside DCP.
 */
public final class ModelPromptRenderer {
    /**
     * Project a question schema into prompt rows. The context is reserved for future conditional
     * prompt shaping; v0.2 returns all canonical questions to preserve renderer identity.
     */
    public PromptView render(NegotiationQuestionSchema schema, NegotiationContext ctx) {
        return new PromptView(schema.questions().stream()
                .filter(question -> applies(question, ctx))
                .map(this::normalize)
                .toList());
    }

    /**
     * Predicate hook: currently includes every question and exists to keep future applies_when logic
     * symmetric with the interview renderer.
     */
    private boolean applies(NegotiationQuestion question, NegotiationContext ctx) {
        return true;
    }

    /**
     * Normalization helper: produces the same semantic row key as InterviewRenderer, allowing tests
     * to assert shared protocol content independent of presentation envelope.
     */
    private String normalize(NegotiationQuestion question) {
        return question.id() + "|" + question.answerType() + "|" + question.feeds() + "|" + question.prompt();
    }
}
