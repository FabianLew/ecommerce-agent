package com.ecommerceagent.integration;

/** A webhook delivery that is not accepted (ADR-0009); the reason maps to the HTTP status. */
public class WebhookRejectedException extends RuntimeException {

    public enum Reason {
        /** Unknown endpoint key. */
        NOT_FOUND,
        /** Missing secret, missing or wrong signature. */
        SIGNATURE_INVALID,
        PAYLOAD_TOO_LARGE
    }

    private static final long serialVersionUID = 1L;

    private final Reason reason;

    public WebhookRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
