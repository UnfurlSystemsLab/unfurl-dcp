package com.unfurl.dcp.broker;

public final class BrokerException extends RuntimeException {
    private final DispositionReason reason;

    public BrokerException(DispositionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DispositionReason reason() {
        return reason;
    }
}
