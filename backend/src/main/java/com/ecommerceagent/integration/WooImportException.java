package com.ecommerceagent.integration;

/** An import step could not read the store; {@link #error()} says why, as for connection checks. */
public class WooImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ConnectionCheckError error;

    public WooImportException(ConnectionCheckError error, String message) {
        super(message);
        this.error = error;
    }

    public ConnectionCheckError error() {
        return error;
    }

    /** Retrying cannot help until someone changes the key, the permissions or the address. */
    public boolean permanent() {
        return switch (error) {
            case AUTH_FAILED, PERMISSION_DENIED, NOT_WOOCOMMERCE, ADDRESS_NOT_ALLOWED -> true;
            case UNREACHABLE, TLS_ERROR, UNEXPECTED_RESPONSE -> false;
        };
    }
}
