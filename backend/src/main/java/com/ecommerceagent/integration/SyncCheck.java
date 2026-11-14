package com.ecommerceagent.integration;

import java.time.Instant;

/** One poll or reconciliation run (ADR-0010). */
public record SyncCheck(Kind kind, Status status, Instant startedAt, Instant finishedAt, int changes,
        int missedWebhooks, String errorCode) {

    public enum Kind {
        POLL,
        RECONCILE
    }

    public enum Status {
        SUCCEEDED,
        /** Read fine, but the source count did not match: watermark and coverage did not move. */
        INCOMPLETE,
        FAILED
    }
}
