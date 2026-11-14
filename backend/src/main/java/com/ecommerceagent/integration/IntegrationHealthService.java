package com.ecommerceagent.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.OrderCoverage;
import com.ecommerceagent.commerce.OrderRepository;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.IntegrationHealth.Reason;
import com.ecommerceagent.integration.IntegrationHealth.Status;
import org.springframework.stereotype.Service;

/** Computes {@link IntegrationHealth} when it is read (ADR-0010); nothing is stored. */
@Service
public class IntegrationHealthService {

    static final Duration POLL_STALE_AFTER = Duration.ofMinutes(15);
    static final Duration ORDERS_STALE_AFTER = Duration.ofHours(2);
    static final Duration EVENT_BACKLOG_AFTER = Duration.ofMinutes(5);
    static final Duration MISSED_WINDOW = Duration.ofHours(24);

    private final SyncStateRepository state;
    private final OrderRepository orders;
    private final OutboxEvents outbox;
    private final Clock clock;

    IntegrationHealthService(SyncStateRepository state, OrderRepository orders, OutboxEvents outbox, Clock clock) {
        this.state = state;
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
    }

    public IntegrationHealth health(TrustedStoreScope scope, IntegrationConnection connection) {
        Instant now = clock.instant();
        Optional<SyncRun> imported = state.lastSuccessfulImport(scope, connection.id());
        Instant lastPoll = state.latest(scope, connection.id(), SyncCheck.Kind.POLL, true)
                .map(SyncCheck::finishedAt).orElse(null);
        Instant lastReconcile = state.latest(scope, connection.id(), SyncCheck.Kind.RECONCILE, true)
                .map(SyncCheck::finishedAt).orElse(null);
        Instant completeUntil = orders.coverage(scope, CommerceSource.WOOCOMMERCE)
                .map(OrderCoverage::completeUntil).orElse(null);
        int missed = state.missedWebhooksSince(scope, connection.id(), MISSED_WINDOW);

        List<Reason> reasons = new ArrayList<>();
        if (imported.isEmpty()) {
            reasons.add(Reason.INITIAL_IMPORT_MISSING);
            return new IntegrationHealth(Status.NOT_READY, reasons, lastPoll, lastReconcile, completeUntil,
                    connection.lastWebhookAt(), missed);
        }
        if (connection.status() != ConnectionStatus.CONNECTED) {
            reasons.add(Reason.CONNECTION_NOT_CONNECTED);
        }
        // Until the first poll, the import's catalog read is the latest confirmed one.
        Instant freshest = lastPoll != null ? lastPoll : imported.get().finishedAt();
        if (freshest == null || freshest.isBefore(now.minus(POLL_STALE_AFTER))) {
            reasons.add(Reason.POLL_STALE);
        }
        if (completeUntil == null || completeUntil.isBefore(now.minus(ORDERS_STALE_AFTER))) {
            reasons.add(Reason.ORDERS_STALE);
        }
        boolean stale = !reasons.isEmpty();
        if (connection.webhookSecretSetAt() == null) {
            reasons.add(Reason.WEBHOOK_SECRET_MISSING);
        }
        if (missed > 0) {
            reasons.add(Reason.MISSED_WEBHOOKS);
        }
        if (state.failedInboxItemsSince(scope, connection.id(), MISSED_WINDOW) > 0) {
            reasons.add(Reason.WEBHOOK_FAILURES);
        }
        if (outbox.oldestPending(scope).filter(at -> at.isBefore(now.minus(EVENT_BACKLOG_AFTER))).isPresent()) {
            reasons.add(Reason.EVENT_BACKLOG);
        }
        Status status = stale ? Status.STALE : reasons.isEmpty() ? Status.HEALTHY : Status.DEGRADED;
        return new IntegrationHealth(status, reasons, lastPoll, lastReconcile, completeUntil,
                connection.lastWebhookAt(), missed);
    }
}
