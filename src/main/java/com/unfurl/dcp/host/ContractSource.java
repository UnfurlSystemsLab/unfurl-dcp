package com.unfurl.dcp.host;

import java.util.Arrays;
import java.util.Objects;

/**
 * Value Object: the raw bytes of one frozen-contract envelope and the name it is reported under (typically its file
 * name). Input to {@link VerifiedContracts#load}; it carries no trust of its own.
 *
 * @param name  the diagnostic name, non-blank
 * @param bytes the envelope bytes, copied on construction and on read
 */
public record ContractSource(String name, byte[] bytes) {
    /** Validating constructor: requires a name and copies the bytes. */
    public ContractSource {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("contract source name is required");
        bytes = Objects.requireNonNull(bytes, "contract bytes").clone();
    }

    /** Returns a copy of the envelope bytes. */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /** Value equality over the name and byte content. */
    @Override
    public boolean equals(Object other) {
        return other instanceof ContractSource that && name.equals(that.name) && Arrays.equals(bytes, that.bytes);
    }

    /** Hash consistent with {@link #equals}. */
    @Override
    public int hashCode() {
        return 31 * name.hashCode() + Arrays.hashCode(bytes);
    }

    /** Diagnostic form: the name and size, never the content. */
    @Override
    public String toString() {
        return "ContractSource[" + name + ", " + bytes.length + " bytes]";
    }
}
