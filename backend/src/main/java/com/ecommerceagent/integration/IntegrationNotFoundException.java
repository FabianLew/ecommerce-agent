package com.ecommerceagent.integration;

import java.util.UUID;

/** No connection with this id in the caller's store; other stores' connections look the same. */
public class IntegrationNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IntegrationNotFoundException(UUID connectionId) {
        super("Integration connection not found: " + connectionId);
    }
}
