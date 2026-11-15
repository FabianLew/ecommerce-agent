package com.ecommerceagent.automation.guardian;

import org.springframework.modulith.NamedInterface;

/** A refused approval or rejection (ADR-0020); {@code code} says why. */
@NamedInterface("guardian")
public class ApprovalException extends RuntimeException {

    public enum Kind {
        NOT_FOUND,
        INVALID,
        FORBIDDEN,
        CONFLICT,
        /** {@code If-Match} does not name the current version. */
        PRECONDITION_FAILED,
        /** {@code If-Match} is missing. */
        PRECONDITION_REQUIRED
    }

    private static final long serialVersionUID = 1L;

    private final Kind kind;
    private final String code;

    public ApprovalException(Kind kind, String code) {
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
