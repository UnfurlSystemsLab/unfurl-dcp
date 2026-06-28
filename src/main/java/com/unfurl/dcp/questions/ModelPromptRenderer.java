package com.unfurl.dcp.questions;

public final class ModelPromptRenderer {
    public PromptView render(NegotiationQuestionSchema schema, NegotiationContext ctx) {
        return new PromptView(schema.questions().stream()
                .filter(question -> applies(question, ctx))
                .map(this::normalize)
                .toList());
    }

    private boolean applies(NegotiationQuestion question, NegotiationContext ctx) {
        return true;
    }

    private String normalize(NegotiationQuestion question) {
        return question.id() + "|" + question.answerType() + "|" + question.feeds() + "|" + question.prompt();
    }
}
