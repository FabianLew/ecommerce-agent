package com.ecommerceagent.businessprofile;

/** A profile change the backend refuses. */
public class BusinessProfileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        NOT_FOUND,
        INVALID,
        VERSION_CONFLICT
    }

    private final Reason reason;
    private final String field;
    private final String code;

    public BusinessProfileException(Reason reason, String field, String code, String message) {
        super(message, null, false, false);
        this.reason = reason;
        this.field = field;
        this.code = code;
    }

    public Reason reason() {
        return reason;
    }

    public String field() {
        return field;
    }

    public String code() {
        return code;
    }
}
