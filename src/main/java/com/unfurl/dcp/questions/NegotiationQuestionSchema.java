package com.unfurl.dcp.questions;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record NegotiationQuestionSchema(@NotEmpty List<NegotiationQuestion> questions) {
    public static final NegotiationQuestionSchema CANONICAL_V0_2 = new NegotiationQuestionSchema(List.of(
            new NegotiationQuestion("expected_disposition", null, "Should this composition be accepted or refused?", AnswerType.DISPOSITION, FeedsTarget.CONFLICT_CHECK),
            new NegotiationQuestion("owner", null, "Who owns the disputed concern?", AnswerType.OWNER, FeedsTarget.CONFLICT_CHECK),
            new NegotiationQuestion("scope", null, "What scope is safe to delegate?", AnswerType.SCOPE, FeedsTarget.BINDING),
            new NegotiationQuestion("redirection", null, "Where should refused work be redirected?", AnswerType.FREE_TEXT, FeedsTarget.DEPENDENCY_CHECK),
            new NegotiationQuestion("expected_redirection", null, "What component should receive refused work?", AnswerType.FREE_TEXT, FeedsTarget.DEPENDENCY_CHECK),
            new NegotiationQuestion("consumer_access", null, "Which consumers may call the offered capability?", AnswerType.SCOPE, FeedsTarget.BINDING),
            new NegotiationQuestion("data_mapping", null, "Which input and output fields are safe to map?", AnswerType.SCOPE, FeedsTarget.DATA_MAPPING),
            new NegotiationQuestion("requires_human_escalation", null, "Does this overlap require human escalation?", AnswerType.BOOLEAN, FeedsTarget.CONFLICT_CHECK),
            new NegotiationQuestion("runtime_assumption", null, "What runtime assumption invalidates this contract?", AnswerType.FREE_TEXT, FeedsTarget.BINDING)
    ));

    public NegotiationQuestionSchema {
        questions = questions == null ? List.of() : List.copyOf(questions);
    }
}
