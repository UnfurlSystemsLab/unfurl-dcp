package com.unfurl.dcp.host;

import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.FrozenContract;

import java.util.Map;
import java.util.Optional;

/**
 * Value: a frozen contract whose signature verified against the host's trusted keys, whose schema validated, and whose
 * decoded body canonicalizes to exactly its signed bytes. Only {@link VerifiedContracts} creates one, and nothing in
 * the host accepts a bare {@code FrozenContract} or {@code CompositionContract}, so every pin the host trusts was read
 * from a verified body (HLD-C §6.1 rule 2).
 *
 * <p>The provider pin is parsed once, at construction. A malformed pin is remembered rather than thrown, because only a
 * contract the host actually admits in-process needs one; {@link #pin()} raises it then.
 */
public final class VerifiedContract {
    private final String source;
    private final FrozenContract frozen;
    private final Optional<ProviderArtifactPin> pin;
    private final String pinError;

    /** Package-private: built by {@link VerifiedContracts} after every verification has passed. */
    VerifiedContract(String source, FrozenContract frozen) {
        this.source = source;
        this.frozen = frozen;
        Map<String, Object> extensions = frozen.contract().metadata() == null
                ? Map.of() : frozen.contract().metadata().extensions();
        Optional<ProviderArtifactPin> parsed;
        String error;
        try {
            parsed = ProviderArtifactPin.fromExtensions(extensions);
            error = null;
        } catch (IllegalArgumentException invalid) {
            parsed = Optional.empty();
            error = invalid.getMessage();
        }
        this.pin = parsed;
        this.pinError = error;
    }

    /** Returns the name of the envelope this contract was loaded from. */
    public String source() {
        return source;
    }

    /** Returns the verified frozen contract. */
    public FrozenContract frozen() {
        return frozen;
    }

    /** Returns the signed body; never a separately decoded copy. */
    public CompositionContract contract() {
        return frozen.contract();
    }

    /** Returns {@code <contractId>@<contractVersion>}, the identity used in diagnostics. */
    public String id() {
        return contract().contractId() + "@" + contract().contractVersion();
    }

    /**
     * Returns the provider artifact pin read from the verified body.
     *
     * @return empty when the contract pins no provider artifact
     * @throws ComponentHostException {@link HostFailureCode#PROVIDER_PIN_INVALID} when the pin is present but malformed
     */
    public Optional<ProviderArtifactPin> pin() {
        if (pinError != null) throw new ComponentHostException(HostFailureCode.PROVIDER_PIN_INVALID, id(), pinError);
        return pin;
    }
}
