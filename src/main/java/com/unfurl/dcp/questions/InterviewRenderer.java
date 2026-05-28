package com.unfurl.dcp.questions;

public final class InterviewRenderer {
    public InterviewView render(NegotiationQuestionSchema schema, NegotiationContext ctx) {
        return new InterviewView(schema.questions().stream().map(NegotiationQuestion::prompt).toList());
    }
}
