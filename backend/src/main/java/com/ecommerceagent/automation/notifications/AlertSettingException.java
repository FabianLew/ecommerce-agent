package com.ecommerceagent.automation.notifications;

import org.springframework.modulith.NamedInterface;

/** A refused change of the store's alert deputy (ADR-0026 point 5); {@code code} says why. */
@NamedInterface("notifications")
public class AlertSettingException extends RuntimeException {

    public enum Kind {
        /** The person named is not a member who may approve decisions. */
        INVALID,
        /** {@code If-Match} does not name the current version. */
        PRECONDITION_FAILED,
        /** {@code If-Match} is missing. */
        PRECONDITION_REQUIRED
    }

    private static final long serialVersionUID = 1L;

    private final Kind kind;
    private final String code;

    public AlertSettingException(Kind kind, String code) {
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
