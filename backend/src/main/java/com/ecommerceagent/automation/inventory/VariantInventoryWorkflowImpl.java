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
 * not followed by a newer stock state within the order delay (ADR-0010). From version {@value #RISK_DETECTION}
 * of {@code risk-detection} it also assesses inventory risk after each stock change and each fresh read, and
 * while a risk series builds up it confirms the stock with another fresh read when the detector asks (B-19,
 * ADR-0011).
 */
@WorkflowImpl(taskQueues = TaskQueues.MAIN)
public class VariantInventoryWorkflowImpl implements VariantInventoryWorkflow {

    static final int RECENT_IDS = 500;
    static final int CONTINUE_AFTER = 500;
    static final String RISK_CHANGE = "risk-detection";
    static final int RISK_DETECTION = 1;

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
    private boolean riskEnabled;
    private boolean riskDue;
    private Long riskCheckAtMillis;
    private int riskChecks;
    private String riskOutcome;
    private String opportunityId;

    @Override
    public void run(VariantInventoryState state) {
        state.recentEventIds().forEach(this::remember);
        latest = parse(state.latestSourceUpdatedAt());
        riskEnabled = Workflow.getVersion(RISK_CHANGE, Workflow.DEFAULT_VERSION, RISK_DETECTION) >= RISK_DETECTION;
        while (true) {
            Long dueAt = nextDue();
            if (dueAt == null) {
                if (handled >= CONTINUE_AFTER && inbox.isEmpty()) {
                    Workflow.continueAsNew(new VariantInventoryState(state.variantId(), List.copyOf(recent),
                            latest == null ? null : latest.toString(), state.verifyDelaySeconds(),
                            state.orderDelaySeconds()));
                }
                Workflow.await(() -> !inbox.isEmpty());
            } else {
                long wait = dueAt - Workflow.currentTimeMillis();
                if (wait > 0) {
                    Workflow.await(Duration.ofMillis(wait), () -> !inbox.isEmpty());
                }
            }
            drain(state);
            long now = Workflow.currentTimeMillis();
            boolean verifyDue = verifyAtMillis != null && now >= verifyAtMillis;
            boolean riskCheckDue = riskCheckAtMillis != null && now >= riskCheckAtMillis;
            if (verifyDue || riskCheckDue) {
                verify(state.variantId());
            } else if (riskDue) {
                assessRisk(state.variantId());
            }
        }
    }

    private Long nextDue() {
        if (verifyAtMillis == null) {
            return riskCheckAtMillis;
        }
        return riskCheckAtMillis == null ? verifyAtMillis : Math.min(verifyAtMillis, riskCheckAtMillis);
    }

    @Override
    public void onEvent(InventorySignal signal) {
        inbox.add(signal);
    }

    @Override
    public VariantInventoryStatus status() {
        return new VariantInventoryStatus(handled, duplicates, verifications, verifyAtMillis != null,
                latest == null ? null : latest.toString(), riskChecks, riskCheckAtMillis != null, riskOutcome,
                opportunityId);
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
                    riskDue = riskEnabled;
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
            if (riskEnabled) {
                riskDue = false;
                riskCheckAtMillis = null;
                record(activities.verifyAndAssess(variantId));
            } else {
                activities.verify(variantId);
            }
            verifications++;
        } catch (ActivityFailure failure) {
            // Polling (B-16) reads the variant again; a failed read must not stop the workflow.
            log.warn("Stock verification of variant {} failed: {}", variantId, failure.getMessage());
        }
    }

    private void assessRisk(String variantId) {
        riskDue = false;
        try {
            record(activities.assessRisk(variantId));
        } catch (ActivityFailure failure) {
            // The next stock change or fresh read assesses again.
            log.warn("Risk assessment of variant {} failed: {}", variantId, failure.getMessage());
        }
    }

    private void record(RiskCheck check) {
        riskChecks++;
        riskOutcome = check.outcome();
        if (check.opportunityId() != null) {
            opportunityId = check.opportunityId();
        }
        // A pending check keeps its time: further stock changes must not postpone the confirming read.
        if (check.nextCheckInSeconds() == null) {
            riskCheckAtMillis = null;
        } else if (riskCheckAtMillis == null) {
            riskCheckAtMillis = Workflow.currentTimeMillis() + check.nextCheckInSeconds() * 1000;
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
