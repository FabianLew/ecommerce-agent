package com.ecommerceagent.automation.sync;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.events.EventDispatch;
import com.ecommerceagent.automation.temporal.StoreContextPropagator;
import com.ecommerceagent.automation.temporal.WorkflowStoreContext;
import com.ecommerceagent.identity.StoreAccess;
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
 * Temporal Schedules for polling ({@code woo-poll-{connectionId}}) and order reconciliation
 * ({@code woo-reconcile-{connectionId}}) of every connected, imported store (ADR-0010). Creating them is
 * idempotent; the store context is stored in the schedule's workflow header.
 */
@Service
public class SyncSchedules {

    private static final Logger log = LoggerFactory.getLogger(SyncSchedules.class);

    private final WooIncrementalSync sync;
    private final StoreAccess storeAccess;
    private final ScheduleClient schedules;
    private final Duration pollInterval;
    private final Duration reconcileInterval;
    private final Set<UUID> ensured = ConcurrentHashMap.newKeySet();

    SyncSchedules(WooIncrementalSync sync, StoreAccess storeAccess, ScheduleClient schedules,
            @Value("${operator.automation.sync.poll-interval:5m}") Duration pollInterval,
            @Value("${operator.automation.sync.reconcile-interval:1h}") Duration reconcileInterval) {
        this.sync = sync;
        this.storeAccess = storeAccess;
        this.schedules = schedules;
        this.pollInterval = pollInterval;
        this.reconcileInterval = reconcileInterval;
    }

    /** Ensures schedules for every target not yet handled by this worker; returns how many were created. */
    public int ensureAll() {
        int created = 0;
        for (SyncTarget target : sync.targets()) {
            if (ensured.contains(target.connectionId())) {
                continue;
            }
            TrustedStoreScope scope = storeAccess
                    .systemScope(EventDispatch.SYNC_SERVICE, target.organizationId(), target.storeId())
                    .orElse(null);
            if (scope == null) {
                continue;
            }
            created += ensure(scope, target.connectionId(), "POLL", pollInterval);
            created += ensure(scope, target.connectionId(), "RECONCILE", reconcileInterval);
            ensured.add(target.connectionId());
        }
        return created;
    }

    public static String scheduleId(UUID connectionId, String kind) {
        return ("RECONCILE".equals(kind) ? "woo-reconcile-" : "woo-poll-") + connectionId;
    }

    private int ensure(TrustedStoreScope scope, UUID connectionId, String kind, Duration interval) {
        String id = scheduleId(connectionId, kind);
        Schedule schedule = Schedule.newBuilder()
                .setAction(ScheduleActionStartWorkflow.newBuilder()
                        .setWorkflowType(IncrementalSyncWorkflow.class)
                        .setArguments(connectionId.toString(), kind)
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
}
