package com.ecommerceagent.automation.execution;

import org.springframework.modulith.NamedInterface;

/** A refused change of the mutation stop (ADR-0020 point 7); {@code code} says why. */
@NamedInterface("execution")
public class MutationStopException extends RuntimeException {

    public enum Kind {
        INVALID,
        /** {@code If-Match} does not name the current version. */
        PRECONDITION_FAILED,
        /** {@code If-Match} is missing. */
        PRECONDITION_REQUIRED
    }

    private static final long serialVersionUID = 1L;

    private final Kind kind;
    private final String code;

    public MutationStopException(Kind kind, String code) {
        super(code);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
