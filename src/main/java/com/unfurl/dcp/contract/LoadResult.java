package com.unfurl.dcp.contract;

import com.unfurl.dcp.trust.VerificationResult;
import com.unfurl.dcp.validation.SchemaValidationReport;

import java.util.Optional;

public record LoadResult(
        Optional<FrozenContract> frozenContract,
        VerificationResult verificationResult,
        SchemaValidationReport validationReport
) {
    public LoadResult {
        frozenContract = frozenContract == null ? Optional.empty() : frozenContract;
        validationReport = validationReport == null ? SchemaValidationReport.ok() : validationReport;
    }

    public boolean loaded() {
        return frozenContract.isPresent()
                && (verificationResult == null || verificationResult.valid())
                && validationReport.valid();
    }
}
