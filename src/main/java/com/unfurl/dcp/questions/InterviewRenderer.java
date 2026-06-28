package com.unfurl.dcp.questions;

public final class InterviewRenderer {
    public InterviewView render(NegotiationQuestionSchema schema, NegotiationContext ctx) {
        return new InterviewView(schema.questions().stream()
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
