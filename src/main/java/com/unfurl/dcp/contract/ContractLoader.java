package com.unfurl.dcp.contract;

import com.unfurl.dcp.trust.OfflineContractVerifier;
import com.unfurl.dcp.trust.VerificationKeySet;
import com.unfurl.dcp.trust.VerificationResult;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.SchemaValidationReport;

import java.util.Optional;

public final class ContractLoader {
    private final VerificationKeySet keySet;
    private final ContractValidator validator;

    public ContractLoader(VerificationKeySet keySet, ContractValidator validator) {
        this.keySet = keySet;
        this.validator = validator == null ? new ContractValidator() : validator;
    }

    public LoadResult load(byte[] frozenBytes, OfflineContractVerifier verifier) {
        try {
            FrozenContractEnvelope envelope = ContractCodec.canonicalMapper().readValue(frozenBytes, FrozenContractEnvelope.class);
            VerificationResult verification = verifier.verify(envelope.signedContract(), keySet);
            SchemaValidationReport report = validator.validate(envelope.contract());
            FrozenContract frozen = new FrozenContract(frozenBytes, envelope.contract(), envelope.signedContract());
            return new LoadResult(Optional.of(frozen), verification, report);
        } catch (Exception ex) {
            SchemaValidationReport report = SchemaValidationReport.of(
                    Diagnostic.error(ErrorCode.VALIDATION_FAILED, "unable to load frozen contract: " + ex.getMessage(), "$"));
            return new LoadResult(Optional.empty(), VerificationResult.invalid(ex.getMessage()), report);
        }
    }

}
