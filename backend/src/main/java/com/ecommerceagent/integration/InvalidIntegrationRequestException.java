package com.ecommerceagent.integration;

/** Input rejected by validation; {@code field} and {@code code} are safe to show to the user. */
public class InvalidIntegrationRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;
    private final String code;

    public InvalidIntegrationRequestException(String field, String code, String message) {
        super(message);
        this.field = field;
        this.code = code;
    }

    public String field() {
        return field;
    }

    public String code() {
        return code;
    }
}
