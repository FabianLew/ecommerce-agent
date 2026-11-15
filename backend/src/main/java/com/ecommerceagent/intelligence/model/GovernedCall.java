package com.ecommerceagent.intelligence.model;

import java.util.Objects;

/**
 * One paid model call as the runtime asks for it: the automation it belongs to (budget scope and ledger), the
 * request, its sequence number in the run (with the run id it forms the reservation's idempotency key) and the
 * runtime's estimate of the request's input tokens.
 */
public record GovernedCall(String automationType, ModelRequest request, int callSequence, long estimatedInputTokens) {

    public GovernedCall {
        Objects.requireNonNull(automationType, "automationType");
        Objects.requireNonNull(request, "request");
        if (callSequence < 1 || estimatedInputTokens < 0) {
            throw new IllegalArgumentException("callSequence must be positive and estimatedInputTokens not negative");
        }
    }

    /** Stable for the run and call, so a repeated command finds the same reservation instead of a second one. */
    public String idempotencyKey() {
        return "agent-run:" + request.runId() + ":call:" + callSequence;
    }
}
