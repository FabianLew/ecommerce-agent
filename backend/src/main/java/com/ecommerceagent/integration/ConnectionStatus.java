package com.ecommerceagent.integration;

/** Lifecycle of a connection; new credentials or a new address always return to {@code CHECK_REQUIRED}. */
public enum ConnectionStatus {
    PENDING_CREDENTIALS,
    CHECK_REQUIRED,
    CONNECTED,
    ERROR
}
