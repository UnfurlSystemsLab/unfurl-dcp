package com.unfurl.dcp.questions;

import java.time.Instant;

public record CapturedAnswer(String questionId, Object value, String rationale, Instant capturedAt) {
}
