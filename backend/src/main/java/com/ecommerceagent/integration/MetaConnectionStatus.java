package com.ecommerceagent.integration;

/** State of a store's Meta ad account connection (ADR-0017). */
public enum MetaConnectionStatus {
    PENDING_CREDENTIALS,
    CHECK_REQUIRED,
    CONNECTED,
    ERROR
}
