package com.ecommerceagent.automation.execution;

import org.springframework.modulith.NamedInterface;

/** A refused change of a store's action permission (ADR-0025 point 2); {@code code} says why. */
@NamedInterface("execution")
public class ActionPermissionException extends RuntimeException {

    public enum Kind {
        INVALID,
        NOT_FOUND,
        /** The store is not ready for the level (monitoring not started, Meta cannot write). */
        CONFLICT,
        /** {@code If-Match} does not name the current version. */
        PRECONDITION_FAILED,
        /** {@code If-Match} is missing. */
        PRECONDITION_REQUIRED
    }

    private static final long serialVersionUID = 1L;

    private final Kind kind;
    private final String code;

    public ActionPermissionException(Kind kind, String code) {
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
