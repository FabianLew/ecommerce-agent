package com.ecommerceagent.integration.woocommerce;

/** A request to a store that did not produce an HTTP response we can interpret. */
public class WooClientException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        ADDRESS_NOT_ALLOWED,
        UNREACHABLE,
        /** The store did not send the complete response before the deadline (headers and body). */
        TIMEOUT,
        TLS_ERROR,
        RESPONSE_TOO_LARGE
    }

    private final Kind kind;

    public WooClientException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
