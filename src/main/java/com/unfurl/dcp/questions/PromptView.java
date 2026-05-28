package com.unfurl.dcp.questions;

import java.util.List;

public record PromptView(List<String> normalized) {
    public PromptView {
        normalized = normalized == null ? List.of() : List.copyOf(normalized);
    }
}
