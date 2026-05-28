package com.unfurl.dcp.questions;

import jakarta.validation.constraints.NotBlank;

public record NegotiationQuestion(
        @NotBlank String id,
        String appliesWhen,
        @NotBlank String prompt,
        AnswerType answerType,
        FeedsTarget feeds
) {
}
