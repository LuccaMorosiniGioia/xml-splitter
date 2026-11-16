package com.transunion.xmlsplitter.core;

/**
 * Any failure of a split run. The {@link Reason} lets each interface (CLI today) map it to its own error contract.
 */
public class SplitException extends RuntimeException {

    public enum Reason {
        INVALID_ARGUMENTS,
        INPUT_UNREADABLE,
        MALFORMED_INPUT,
        INVALID_OUTPUT,
        OUTPUT_CONFLICT,
        IO_FAILURE
    }

    private final Reason reason;

    public SplitException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SplitException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
