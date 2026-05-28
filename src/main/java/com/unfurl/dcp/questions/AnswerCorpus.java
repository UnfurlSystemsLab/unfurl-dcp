package com.unfurl.dcp.questions;

import java.util.List;

public record AnswerCorpus(List<CapturedAnswer> answers) {
    public AnswerCorpus {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }
}
