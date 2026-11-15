package com.ecommerceagent.web;

/** A malformed list query parameter (cursor, limit, filter); answered with 400 and the code. */
class InvalidQueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    InvalidQueryException(String code) {
        super(code, null, false, false);
        this.code = code;
    }

    String code() {
        return code;
    }
}
