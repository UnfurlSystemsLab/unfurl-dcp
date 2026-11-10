package com.unfurl.dcp.host;

import java.util.Objects;

/**
 * The single failure type of the in-process component host. It carries one {@link HostFailureCode} and the subject it
 * concerns (a contract file, contract id, artifact coordinates or claim URI), so a host can stop startup with exactly
 * what failed. Rollback failures are attached as suppressed exceptions. Messages never carry configuration values,
 * secrets or artifact contents.
 */
public final class ComponentHostException extends RuntimeException {
    private final HostFailureCode code;
    private final String subject;

    /**
     * @param code    the failure code
     * @param subject what the failure concerns, never blank
     * @param detail  a human-readable explanation without secret or configuration values
     */
    public ComponentHostException(HostFailureCode code, String subject, String detail) {
        this(code, subject, detail, null);
    }

    /** As {@link #ComponentHostException(HostFailureCode, String, String)}, with the underlying cause. */
    public ComponentHostException(HostFailureCode code, String subject, String detail, Throwable cause) {
        super(code + " [" + subject + "]: " + detail, cause);
        this.code = Objects.requireNonNull(code, "code");
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("failure subject is required");
        this.subject = subject;
    }

    /** Returns the failure code. */
    public HostFailureCode code() {
        return code;
    }

    /** Returns what the failure concerns. */
    public String subject() {
        return subject;
    }
}
