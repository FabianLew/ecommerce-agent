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
    private final boolean confirmedNotCharged;

    public ModelFailure(Type type, String message, Duration retryAfter, String providerRequestId, Throwable cause) {
        this(type, message, retryAfter, providerRequestId, false, cause);
    }

    /**
     * @param confirmedNotCharged the provider answered with an error response that it does not bill (ADR-0027
     *     section 4.5); only an adapter that knows its provider's billing sets it. Without it, a failure after the
     *     request may have left is treated as possibly charged.
     */
    public ModelFailure(Type type, String message, Duration retryAfter, String providerRequestId,
            boolean confirmedNotCharged, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.retryAfter = retryAfter;
        this.providerRequestId = providerRequestId;
        this.confirmedNotCharged = confirmedNotCharged;
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

    public boolean confirmedNotCharged() {
        return confirmedNotCharged;
    }
}
