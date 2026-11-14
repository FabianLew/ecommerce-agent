package com.ecommerceagent.integration;

import java.time.Instant;
import java.util.List;

/**
 * Freshness of a connection's data (ADR-0010, 04 §10). {@code STALE} blocks analysis that depends on current
 * data; every reason has a repair step in the UI.
 */
public record IntegrationHealth(Status status, List<Reason> reasons, Instant lastPollAt, Instant lastReconcileAt,
        Instant ordersCompleteUntil, Instant lastWebhookAt, int missedWebhooks24h) {

    public IntegrationHealth {
        reasons = List.copyOf(reasons);
    }

    public enum Status {
        NOT_READY,
        STALE,
        DEGRADED,
        HEALTHY
    }

    public enum Reason {
        /** No successful initial import yet. */
        INITIAL_IMPORT_MISSING,
        /** The connection check failed or is required again. */
        CONNECTION_NOT_CONNECTED,
        /** No successful poll for longer than the stale threshold. */
        POLL_STALE,
        /** Orders are known to be complete only up to a moment too long ago. */
        ORDERS_STALE,
        WEBHOOK_SECRET_MISSING,
        /** Polling or reconciliation found changes that webhooks did not deliver. */
        MISSED_WEBHOOKS,
        /** Webhook deliveries that could not be processed. */
        WEBHOOK_FAILURES,
        /** Domain events waiting for dispatch for too long. */
        EVENT_BACKLOG
    }
}
