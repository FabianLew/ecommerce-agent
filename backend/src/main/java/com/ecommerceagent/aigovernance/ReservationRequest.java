package com.ecommerceagent.aigovernance;

import java.util.Objects;
import java.util.UUID;

/**
 * One paid model call to admit. The idempotency key names the logical call (for example the run and the call's
 * sequence number in it), so repeating the command never charges the budget twice. Every retry of a model call is a
 * new call with its own key and its own reservation.
 */
public record ReservationRequest(String idempotencyKey, String automationType, UUID agentRunId,
        AiCallEstimate estimate) {

    public ReservationRequest {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(automationType, "automationType");
        Objects.requireNonNull(estimate, "estimate");
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("The idempotency key must have 1 to 200 characters");
        }
        if (!automationType.matches("[a-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Invalid automation type " + automationType);
        }
    }
}
