package com.unfurl.dcp.questions;

public final class ModelPromptRenderer {
    public PromptView render(NegotiationQuestionSchema schema, NegotiationContext ctx) {
        return new PromptView(schema.questions().stream().map(NegotiationQuestion::prompt).toList());
    }
}
