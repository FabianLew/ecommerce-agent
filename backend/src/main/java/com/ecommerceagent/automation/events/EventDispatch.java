package com.ecommerceagent.automation.events;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.inventory.InventorySignal;
import com.ecommerceagent.automation.inventory.VariantInventoryState;
import com.ecommerceagent.automation.inventory.VariantInventoryWorkflow;
import com.ecommerceagent.automation.sync.CatalogSignal;
import com.ecommerceagent.automation.sync.StoreSyncState;
import com.ecommerceagent.automation.sync.StoreSyncWorkflow;
import com.ecommerceagent.automation.temporal.StoreContextPropagator;
import com.ecommerceagent.automation.temporal.WorkflowStoreContext;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.IntegrationConnection;
import com.ecommerceagent.integration.IntegrationConnections;
import com.ecommerceagent.integration.IntegrationProvider;
import com.ecommerceagent.integration.OutboxEvents;
import com.ecommerceagent.integration.OutboxEvents.OutboxEvent;
import com.ecommerceagent.integration.OutboxEvents.PendingEvent;
import io.temporal.client.BatchRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox → Temporal (B-15, ADR-0010). Each event is claimed, signalled with {@code signalWithStart} to a
 * workflow with a deterministic ID and marked dispatched in one transaction. A repeated dispatch reaches the
 * same workflow, which ignores the known event ID, so no event creates a second workflow.
 */
@Service
public class EventDispatch {

    /** System actor of the workflows that keep store data in sync (read scope, ADR-0004). */
    public static final String SYNC_SERVICE = "woo-sync";

    private static final Logger log = LoggerFactory.getLogger(EventDispatch.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final OutboxEvents outbox;
    private final IntegrationConnections connections;
    private final StoreAccess storeAccess;
    private final WorkflowClient client;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final long verifyDelaySeconds;
    private final long orderDelaySeconds;
    private final long catalogDelaySeconds;

    EventDispatch(OutboxEvents outbox, IntegrationConnections connections, StoreAccess storeAccess,
            WorkflowClient client, TransactionTemplate transactions, Clock clock,
            @Value("${operator.automation.inventory.verify-delay:10s}") Duration verifyDelay,
            @Value("${operator.automation.inventory.order-delay:60s}") Duration orderDelay,
            @Value("${operator.automation.catalog.refresh-delay:30s}") Duration catalogDelay) {
        this.outbox = outbox;
        this.connections = connections;
        this.storeAccess = storeAccess;
        this.client = client;
        this.transactions = transactions;
        this.clock = clock;
        this.verifyDelaySeconds = verifyDelay.toSeconds();
        this.orderDelaySeconds = orderDelay.toSeconds();
        this.catalogDelaySeconds = catalogDelay.toSeconds();
    }

    /** Dispatches up to {@code limit} due events; returns how many were handled (dispatched or postponed). */
    public int dispatchPending(int limit) {
        int handled = 0;
        for (; handled < limit; handled++) {
            AtomicReference<PendingEvent> claimed = new AtomicReference<>();
            try {
                Boolean found = transactions.execute(status -> {
                    Optional<PendingEvent> next = outbox.claimNext();
                    if (next.isEmpty()) {
                        return false;
                    }
                    claimed.set(next.get());
                    dispatch(next.get());
                    outbox.markDispatched(next.get().event().id());
                    return true;
                });
                if (!Boolean.TRUE.equals(found)) {
                    break;
                }
            } catch (RuntimeException e) {
                PendingEvent pending = claimed.get();
                if (pending == null) {
                    throw e;
                }
                Duration backoff = Duration.ofSeconds(5L << Math.min(pending.attempts(), 10));
                Instant next = clock.instant().plus(backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff);
                log.warn("Dispatch of outbox event {} failed (attempt {}), retry at {}", pending.event().id(),
                        pending.attempts() + 1, next, e);
                transactions.executeWithoutResult(status -> outbox.recordFailure(pending.event().id(), next));
            }
        }
        return handled;
    }

    private void dispatch(PendingEvent pending) {
        OutboxEvent event = pending.event();
        TrustedStoreScope scope = storeAccess.systemScope(SYNC_SERVICE, pending.organizationId(), pending.storeId())
                .orElseThrow(() -> new IllegalStateException("Store of outbox event no longer exists"));
        JsonNode payload = JSON.readTree(event.payload());
        String sourceUpdatedAt = text(payload, "sourceUpdatedAt");
        switch (event.eventType()) {
            case OutboxEvents.INVENTORY_CHANGED -> signalVariant(scope, event.aggregateId(),
                    new InventorySignal(event.id().toString(), event.eventType(), sourceUpdatedAt));
            case OutboxEvents.INVENTORY_VERIFICATION_REQUESTED -> {
                if ("variant".equals(event.aggregateType())) {
                    signalVariant(scope, event.aggregateId(),
                            new InventorySignal(event.id().toString(), event.eventType(), null));
                } else {
                    refreshProduct(scope, new CatalogSignal(event.id().toString(), event.aggregateId()));
                }
            }
            case OutboxEvents.ORDER_CHANGED -> {
                for (JsonNode variantId : payload.path("variantIds")) {
                    String id = variantId.asString();
                    signalVariant(scope, id, new InventorySignal(event.id() + ":" + id, event.eventType(),
                            sourceUpdatedAt));
                }
            }
            default -> log.info("Outbox event type {} has no workflow target", event.eventType());
        }
    }

    private void signalVariant(TrustedStoreScope scope, String variantId, InventorySignal signal) {
        UUID.fromString(variantId);
        VariantInventoryWorkflow workflow = client.newWorkflowStub(VariantInventoryWorkflow.class, options(
                VariantInventoryWorkflow.workflowId(scope.storeId(), variantId)));
        VariantInventoryState initial = new VariantInventoryState(variantId, List.of(), null, verifyDelaySeconds,
                orderDelaySeconds);
        signalWithStart(scope, signal.eventId(), batch -> {
            batch.add(workflow::run, initial);
            batch.add(workflow::onEvent, signal);
        });
    }

    private void refreshProduct(TrustedStoreScope scope, CatalogSignal signal) {
        IntegrationConnection connection = connections.list(scope).stream()
                .filter(found -> found.provider() == IntegrationProvider.WOOCOMMERCE)
                .findFirst()
                .orElse(null);
        if (connection == null) {
            log.info("No WooCommerce connection for catalog event {}; skipped", signal.eventId());
            return;
        }
        StoreSyncWorkflow workflow = client.newWorkflowStub(StoreSyncWorkflow.class,
                options(StoreSyncWorkflow.workflowId(connection.id())));
        StoreSyncState initial = new StoreSyncState(connection.id().toString(), List.of(), catalogDelaySeconds);
        signalWithStart(scope, signal.eventId(), batch -> {
            batch.add(workflow::run, initial);
            batch.add(workflow::refreshProduct, signal);
        });
    }

    private void signalWithStart(TrustedStoreScope scope, String eventId, Consumer<BatchRequest> build) {
        BatchRequest batch = client.newSignalWithStartRequest();
        build.accept(batch);
        StoreContextPropagator.callWith(WorkflowStoreContext.of(scope, "outbox-" + eventId),
                () -> client.signalWithStart(batch));
    }

    private static WorkflowOptions options(String workflowId) {
        return WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.MAIN)
                .setWorkflowId(workflowId)
                .build();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }
}
