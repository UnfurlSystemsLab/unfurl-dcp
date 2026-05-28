package com.unfurl.dcp.questions;

public record TrainingTuple(
        Object claim,
        Object request,
        Object expectedDisposition,
        Object expectedRedirection,
        String rationale
) {
}
