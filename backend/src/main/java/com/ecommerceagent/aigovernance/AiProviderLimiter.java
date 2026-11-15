package com.ecommerceagent.aigovernance;

import java.time.Duration;
import java.util.UUID;

/**
 * Provider rate limiting (ADR-0027 section 6.2): a paid call takes a slot of its provider model before it is sent
 * and gives it back when it ends; a model the provider rate limited is paused. Limits are platform wide, across all
 * instances. Any failure to decide throws: the caller sends nothing.
 */
public interface AiProviderLimiter {

    Slot acquire(UUID callId, String provider, String model, long inputTokens, long outputTokens, Duration ttl);

    /** Ends the call's slot; it keeps counting in the per-minute window. */
    void release(UUID callId);

    /** Pauses the model after the provider answered with rate limiting; {@code retryAfter} may be null. */
    void pause(String provider, String model, Duration retryAfter, String reason);

    enum Slot {
        GRANTED,
        /** The provider rate limited this model and its pause has not passed. */
        PROVIDER_PAUSED,
        REQUEST_LIMIT,
        INPUT_TOKEN_LIMIT,
        OUTPUT_TOKEN_LIMIT,
        PARALLEL_LIMIT;

        public boolean granted() {
            return this == GRANTED;
        }
    }
}
