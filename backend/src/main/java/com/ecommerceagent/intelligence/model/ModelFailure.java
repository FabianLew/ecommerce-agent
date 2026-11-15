package com.ecommerceagent.intelligence.model;

import java.time.Duration;

/** A model call that gave no turn. Carries no prompt text, tokens or secrets. */
public class ModelFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Type {
        TIMEOUT,
        RATE_LIMITED,
        UNAVAILABLE,
        /** The provider refused the request as invalid or unauthorized; retrying the same request will not help. */
        REJECTED,
        UNKNOWN
    }

    private final Type type;
    private final Duration retryAfter;
    private final String providerRequestId;

    public ModelFailure(Type type, String message, Duration retryAfter, String providerRequestId, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.retryAfter = retryAfter;
        this.providerRequestId = providerRequestId;
    }

    public Type type() {
        return type;
    }

    public boolean retryable() {
        return type != Type.REJECTED;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public String providerRequestId() {
        return providerRequestId;
    }
}
