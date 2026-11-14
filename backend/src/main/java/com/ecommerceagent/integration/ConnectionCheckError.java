package com.ecommerceagent.integration;

/** Why a read-only connection check failed; each code maps to one repair step in the UI (ADR-0007). */
public enum ConnectionCheckError {
    /** The key or secret is wrong, or the key was revoked. */
    AUTH_FAILED,
    /** The key authenticates but may not read products, orders or settings. */
    PERMISSION_DENIED,
    /** No connection, timeout or DNS failure. */
    UNREACHABLE,
    /** The TLS handshake failed (certificate, protocol). */
    TLS_ERROR,
    /** The address answers, but not with the WooCommerce REST API. */
    NOT_WOOCOMMERCE,
    /** The address resolves to a network the backend must not call (SSRF protection). */
    ADDRESS_NOT_ALLOWED,
    /** Any other status code or an unreadable body. */
    UNEXPECTED_RESPONSE
}
