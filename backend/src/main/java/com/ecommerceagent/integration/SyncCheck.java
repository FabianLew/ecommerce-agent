package com.ecommerceagent.integration;

import java.time.Instant;

/** One poll, reconciliation (ADR-0010) or history chunk (ADR-0015) run. */
public record SyncCheck(Kind kind, Status status, Instant startedAt, Instant finishedAt, int changes,
        int missedWebhooks, String errorCode) {

    public enum Kind {
        POLL,
        RECONCILE,
        /** A chunk of the order history import; Integration Health ignores it. */
        HISTORY
    }

    public enum Status {
        SUCCEEDED,
        /** Read fine, but the source count did not match: watermark and coverage did not move. */
        INCOMPLETE,
        FAILED
    }
}
