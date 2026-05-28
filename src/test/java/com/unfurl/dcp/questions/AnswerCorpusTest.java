package com.unfurl.dcp.questions;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerCorpusTest {
    @Test
    void projectsCapturedAnswersToTrainingTupleShape() {
        AnswerCorpus corpus = new AnswerCorpus(List.of(
                new CapturedAnswer("expected_disposition", "REFUSE", "outside provider boundary", Instant.EPOCH),
                new CapturedAnswer("expected_redirection", "urn:billing", null, Instant.EPOCH)));

        TrainingTuple tuple = corpus.toTrainingTuple(Map.of("claim", "provider"), Map.of("request", "billing"));

        assertThat(tuple.claim()).isEqualTo(Map.of("claim", "provider"));
        assertThat(tuple.request()).isEqualTo(Map.of("request", "billing"));
        assertThat(tuple.expectedDisposition()).isEqualTo("REFUSE");
        assertThat(tuple.expectedRedirection()).isEqualTo("urn:billing");
        assertThat(tuple.rationale()).isEqualTo("outside provider boundary");
    }
}
