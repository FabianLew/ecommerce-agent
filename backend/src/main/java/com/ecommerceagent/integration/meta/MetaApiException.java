package com.ecommerceagent.integration.meta;

import java.time.Duration;

/** A Graph API call that did not succeed, typed for the caller; carries no token or response body. */
public class MetaApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        AUTH_FAILED,
        PERMISSION_DENIED,
        RATE_LIMITED,
        NOT_FOUND,
        UNREACHABLE,
        /** The request may or may not have reached Meta (timeout after sending a write). */
        UNCERTAIN,
        UNEXPECTED_RESPONSE
    }

    private final Kind kind;
    private final Duration retryAfter;

    public MetaApiException(Kind kind, String message, Duration retryAfter) {
        super(message);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
