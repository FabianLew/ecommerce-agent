package com.ecommerceagent.commerce;

/** A mapping change the backend refuses; {@code code} is shown to the operator, never an identifier of another store. */
public class AdMappingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The ad does not exist in the caller's store. */
        NOT_FOUND,
        /** The request is malformed (field and code given). */
        INVALID,
        /** The mapping changed since the operator loaded it. */
        VERSION_CONFLICT
    }

    private final Reason reason;
    private final String field;
    private final String code;

    public AdMappingException(Reason reason, String field, String code, String message) {
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
