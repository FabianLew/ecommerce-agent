package com.ecommerceagent.automation.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.integration.OutboxEvents;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

/**
 * Deduplicates events by ID (the last {@value #RECENT_IDS} are kept across {@code continueAsNew}), and reads
 * the source when stock is uncertain: on an explicit verification request, and after an order change that is
 * not followed by a newer stock state within the order delay (ADR-0010).
 */
@WorkflowImpl(taskQueues = TaskQueues.MAIN)
public class VariantInventoryWorkflowImpl implements VariantInventoryWorkflow {

    static final int RECENT_IDS = 500;
    static final int CONTINUE_AFTER = 500;

    private static final Logger log = Workflow.getLogger(VariantInventoryWorkflowImpl.class);

    private final VariantInventoryActivities activities = Workflow.newActivityStub(VariantInventoryActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(5))
                            .setBackoffCoefficient(2)
                            .setMaximumAttempts(5)
                            .build())
                    .build());

    private final List<InventorySignal> inbox = new ArrayList<>();
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final Set<String> recentSet = new HashSet<>();
    private Instant latest;
    private Long verifyAtMillis;
    private boolean orderOnly;
    private Instant orderTime;
    private int handled;
    private int duplicates;
    private int verifications;

    @Override
    public void run(VariantInventoryState state) {
        state.recentEventIds().forEach(this::remember);
        latest = parse(state.latestSourceUpdatedAt());
        while (true) {
            if (verifyAtMillis == null) {
                if (handled >= CONTINUE_AFTER && inbox.isEmpty()) {
                    Workflow.continueAsNew(new VariantInventoryState(state.variantId(), List.copyOf(recent),
                            latest == null ? null : latest.toString(), state.verifyDelaySeconds(),
                            state.orderDelaySeconds()));
                }
                Workflow.await(() -> !inbox.isEmpty());
            } else {
                long wait = verifyAtMillis - Workflow.currentTimeMillis();
                if (wait > 0) {
                    Workflow.await(Duration.ofMillis(wait), () -> !inbox.isEmpty());
                }
            }
            drain(state);
            if (verifyAtMillis != null && Workflow.currentTimeMillis() >= verifyAtMillis) {
                verify(state.variantId());
            }
        }
    }

    @Override
    public void onEvent(InventorySignal signal) {
        inbox.add(signal);
    }

    @Override
    public VariantInventoryStatus status() {
        return new VariantInventoryStatus(handled, duplicates, verifications, verifyAtMillis != null,
                latest == null ? null : latest.toString());
    }

    private void drain(VariantInventoryState state) {
        List<InventorySignal> batch = List.copyOf(inbox);
        inbox.clear();
        long now = Workflow.currentTimeMillis();
        for (InventorySignal signal : batch) {
            if (recentSet.contains(signal.eventId())) {
                duplicates++;
                continue;
            }
            remember(signal.eventId());
            handled++;
            Instant at = parse(signal.sourceUpdatedAt());
            switch (signal.eventType()) {
                case OutboxEvents.INVENTORY_CHANGED -> {
                    if (at != null && (latest == null || at.isAfter(latest))) {
                        latest = at;
                    }
                    if (orderOnly && latest != null && orderTime != null && !latest.isBefore(orderTime)) {
                        // The stock change caused by the order arrived: no read needed.
                        verifyAtMillis = null;
                        orderOnly = false;
                    }
                }
                case OutboxEvents.INVENTORY_VERIFICATION_REQUESTED -> {
                    schedule(now + state.verifyDelaySeconds() * 1000);
                    orderOnly = false;
                }
                case OutboxEvents.ORDER_CHANGED -> {
                    boolean covered = at != null && latest != null && !latest.isBefore(at);
                    if (!covered) {
                        if (verifyAtMillis == null) {
                            orderOnly = true;
                        }
                        if (at != null && (orderTime == null || at.isAfter(orderTime))) {
                            orderTime = at;
                        }
                        schedule(now + state.orderDelaySeconds() * 1000);
                    }
                }
                default -> log.warn("Ignoring event type {} for variant {}", signal.eventType(), state.variantId());
            }
        }
    }

    /** Keeps the earliest due time, so a stream of events cannot postpone a read forever. */
    private void schedule(long at) {
        verifyAtMillis = verifyAtMillis == null ? at : Math.min(verifyAtMillis, at);
    }

    private void verify(String variantId) {
        verifyAtMillis = null;
        orderOnly = false;
        orderTime = null;
        try {
            activities.verify(variantId);
            verifications++;
        } catch (ActivityFailure failure) {
            // Polling (B-16) reads the variant again; a failed read must not stop the workflow.
            log.warn("Stock verification of variant {} failed: {}", variantId, failure.getMessage());
        }
    }

    private void remember(String eventId) {
        if (recentSet.add(eventId)) {
            recent.addLast(eventId);
            if (recent.size() > RECENT_IDS) {
                recentSet.remove(recent.removeFirst());
            }
        }
    }

    private static Instant parse(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
