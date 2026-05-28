package com.unfurl.dcp.questions;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record NegotiationQuestionSchema(@NotEmpty List<NegotiationQuestion> questions) {
    public static final NegotiationQuestionSchema CANONICAL_V0_2 = new NegotiationQuestionSchema(List.of(
            new NegotiationQuestion("owner", null, "Who owns the disputed concern?", AnswerType.OWNER, FeedsTarget.CONFLICT_CHECK),
            new NegotiationQuestion("scope", null, "What scope is safe to delegate?", AnswerType.SCOPE, FeedsTarget.BINDING),
            new NegotiationQuestion("redirection", null, "Where should refused work be redirected?", AnswerType.FREE_TEXT, FeedsTarget.DEPENDENCY_CHECK)
    ));

    public NegotiationQuestionSchema {
        questions = questions == null ? List.of() : List.copyOf(questions);
    }
}
