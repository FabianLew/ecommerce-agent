package com.ecommerceagent.automation.sync;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.events.EventDispatch;
import com.ecommerceagent.automation.temporal.StoreContextPropagator;
import com.ecommerceagent.automation.temporal.WorkflowStoreContext;
import com.ecommerceagent.identity.SystemStoreRunner;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.SyncTarget;
import com.ecommerceagent.integration.WooIncrementalSync;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleIntervalSpec;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Temporal Schedules for polling ({@code woo-poll-{connectionId}}), order reconciliation
 * ({@code woo-reconcile-{connectionId}}, ADR-0010) and the order history import ({@code woo-history-{connectionId}},
 * ADR-0015) of every connected, imported store. Creating them is idempotent; the store context is stored in the
 * schedule's workflow header.
 */
@Service
public class SyncSchedules {

    private static final Logger log = LoggerFactory.getLogger(SyncSchedules.class);

    private final WooIncrementalSync sync;
    private final SystemStoreRunner stores;
    private final ScheduleClient schedules;
    private final Duration pollInterval;
    private final Duration reconcileInterval;
    private final HistorySettings history;
    private final Set<UUID> ensured = ConcurrentHashMap.newKeySet();

    SyncSchedules(WooIncrementalSync sync, SystemStoreRunner stores, ScheduleClient schedules,
            @Value("${operator.automation.sync.poll-interval:5m}") Duration pollInterval,
            @Value("${operator.automation.sync.reconcile-interval:1h}") Duration reconcileInterval,
            HistorySettings history) {
        this.sync = sync;
        this.stores = stores;
        this.schedules = schedules;
        this.pollInterval = pollInterval;
        this.reconcileInterval = reconcileInterval;
        this.history = history;
    }

    /** Ensures schedules for every target not yet handled by this worker; returns how many were created. */
    public int ensureAll() {
        AtomicInteger created = new AtomicInteger();
        stores.forEachStore(EventDispatch.SYNC_SERVICE, scope -> {
            for (SyncTarget target : sync.targets(scope)) {
                if (ensured.contains(target.connectionId())) {
                    continue;
                }
                created.addAndGet(ensure(scope, target.connectionId(), "POLL", pollInterval));
                created.addAndGet(ensure(scope, target.connectionId(), "RECONCILE", reconcileInterval));
                if (history.enabled()) {
                    created.addAndGet(ensure(scope, target.connectionId(), "HISTORY", history.interval()));
                }
                ensured.add(target.connectionId());
            }
        });
        return created.get();
    }

    public static String scheduleId(UUID connectionId, String kind) {
        return switch (kind) {
            case "RECONCILE" -> "woo-reconcile-";
            case "HISTORY" -> "woo-history-";
            default -> "woo-poll-";
        } + connectionId;
    }

    private int ensure(TrustedStoreScope scope, UUID connectionId, String kind, Duration interval) {
        String id = scheduleId(connectionId, kind);
        Schedule schedule = Schedule.newBuilder()
                .setAction(action(connectionId, kind)
                        .setOptions(WorkflowOptions.newBuilder()
                                .setWorkflowId(id)
                                .setTaskQueue(TaskQueues.MAIN)
                                .setWorkflowExecutionTimeout(Duration.ofMinutes(15))
                                .build())
                        .build())
                .setSpec(ScheduleSpec.newBuilder()
                        .setIntervals(List.of(new ScheduleIntervalSpec(interval)))
                        .build())
                .setPolicy(SchedulePolicy.newBuilder()
                        .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                        .setCatchupWindow(Duration.ofMinutes(10))
                        .build())
                .build();
        try {
            StoreContextPropagator.callWith(WorkflowStoreContext.of(scope, "schedule-" + id), () ->
                    schedules.createSchedule(id, schedule, ScheduleOptions.newBuilder().setTriggerImmediately(true)
                            .build()));
            log.info("Created schedule {} every {}", id, interval);
            return 1;
        } catch (ScheduleAlreadyRunningException e) {
            return 0;
        }
    }

    private ScheduleActionStartWorkflow.Builder action(UUID connectionId, String kind) {
        if ("HISTORY".equals(kind)) {
            return ScheduleActionStartWorkflow.newBuilder()
                    .setWorkflowType(HistoryImportWorkflow.class)
                    .setArguments(connectionId.toString(), history.maxChunksPerRun(),
                            (int) history.pause().toSeconds());
        }
        return ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType(IncrementalSyncWorkflow.class)
                .setArguments(connectionId.toString(), kind);
    }
}
