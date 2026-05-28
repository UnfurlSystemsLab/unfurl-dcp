package com.unfurl.dcp.questions;

import java.util.List;

public record InterviewView(List<String> normalized) {
    public InterviewView {
        normalized = normalized == null ? List.of() : List.copyOf(normalized);
    }
}
