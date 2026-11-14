package com.ecommerceagent.integration;

/** An import of this connection is already running. */
public class SyncInProgressException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SyncInProgressException() {
        super("An import is already running for this connection");
    }
}
