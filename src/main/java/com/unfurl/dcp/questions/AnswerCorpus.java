package com.unfurl.dcp.questions;

import java.util.List;

/**
 * Builder/projector record: collects captured answers for one negotiation session and projects them
 * into the training tuple shape used by the LoRA/data pipeline. Missing optional answers remain null
 * so downstream tooling can distinguish unanswered fields from empty strings.
 */
public record AnswerCorpus(List<CapturedAnswer> answers) {
    /**
     * Defensive-copy constructor: preserves append-only session semantics by preventing external
     * mutation of the captured answer list after the corpus is created.
     */
    public AnswerCorpus {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }

    /**
     * Project captured answers into the training tuple. The caller supplies the claim and request
     * payloads because DCP stores answer facts, not product-specific training envelopes.
     */
    public TrainingTuple toTrainingTuple(Object claim, Object request) {
        return new TrainingTuple(
                claim,
                request,
                valueFor("expected_disposition", "disposition"),
                valueFor("expected_redirection", "redirection"),
                rationale());
    }

    /**
     * Lookup helper: supports both canonical and legacy answer ids so older captured sessions can
     * still feed the current training tuple shape.
     */
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

    /**
     * Rationale helper: returns the first non-empty explanation, preserving the authored reasoning
     * that explains an accept/refuse/redirection decision.
     */
    private String rationale() {
        return answers.stream()
                .map(CapturedAnswer::rationale)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }
}
