package com.ecommerceagent.aigovernance;

/**
 * Whether the caller may send the call of a reservation now ({@link AiUsageLedger#markSent}). Only {@link #SEND}
 * allows a request to the provider; every other answer means: do not send.
 */
public enum SendDecision {
    /** This caller, and only this one, sends the call. The reservation is now {@code IN_PROGRESS}. */
    SEND,
    /** An earlier attempt may already have sent it (a retry, a second worker): never send it again. */
    ALREADY_ATTEMPTED,
    /** The reservation expired before it was sent; a new call needs a new reservation. */
    EXPIRED,
    /** The reservation was released; a new call needs a new reservation. */
    RELEASED,
    /** AI is stopped for the platform (ADR-0027 section 12): nothing is sent, and the reservation was released. */
    STOPPED
}
