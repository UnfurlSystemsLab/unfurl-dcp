package com.unfurl.dcp.questions;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NotBlank;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RendererIdentityPropertyTest {
    @Property
    void interviewAndPromptViewsHaveSameNormalizedContent(@ForAll @NotBlank @AlphaChars String prompt) {
        NegotiationQuestionSchema schema = new NegotiationQuestionSchema(List.of(
                new NegotiationQuestion("q", null, prompt, AnswerType.FREE_TEXT, FeedsTarget.BINDING)));

        InterviewView interview = new InterviewRenderer().render(schema, new NegotiationContext(null, null, List.of()));
        PromptView promptView = new ModelPromptRenderer().render(schema, new NegotiationContext(null, null, List.of()));

        assertThat(interview.normalized()).isEqualTo(promptView.normalized());
    }
}
