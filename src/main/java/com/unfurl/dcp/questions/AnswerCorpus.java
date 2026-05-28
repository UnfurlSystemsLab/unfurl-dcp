package com.unfurl.dcp.questions;

import java.util.List;

public record AnswerCorpus(List<CapturedAnswer> answers) {
    public AnswerCorpus {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }

    public TrainingTuple toTrainingTuple(Object claim, Object request) {
        return new TrainingTuple(
                claim,
                request,
                valueFor("expected_disposition", "disposition"),
                valueFor("expected_redirection", "redirection"),
                rationale());
    }

    private Object valueFor(String... ids) {
        for (String id : ids) {
            for (CapturedAnswer answer : answers) {
                if (id.equals(answer.questionId())) {
                    return answer.value();
                }
            }
        }
        return null;
    }

    private String rationale() {
        return answers.stream()
                .map(CapturedAnswer::rationale)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }
}
