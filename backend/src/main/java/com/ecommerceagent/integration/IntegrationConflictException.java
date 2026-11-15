package com.ecommerceagent.integration;

/** The store already has a connection for this provider. */
public class IntegrationConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IntegrationConflictException(IntegrationProvider provider) {
        super("The store already has a " + provider + " connection");
    }

    public IntegrationConflictException(String message) {
        super(message);
    }
}
