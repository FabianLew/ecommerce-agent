package com.ecommerceagent.aigovernance;

/** A platform AI decision (a reconciliation, a correction, a scoped stop) that cannot be recorded as asked. */
public class AiReconciliationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        NOT_FOUND,
        /** The reservation is not in a state that allows it, or a different decision was already recorded. */
        CONFLICT,
        INVALID
    }

    private final Kind kind;

    public AiReconciliationException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
