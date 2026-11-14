package com.ecommerceagent.automation;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.SyncRunDispatch;
import com.ecommerceagent.integration.SyncRunStatus;
import com.ecommerceagent.integration.WooCommerceImport;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Brings running import runs in line with Temporal (FIX-02):
 *
 * <ul>
 *   <li>an unconfirmed run younger than {@code startDeadline} is started with its deterministic workflow ID
 *       (a no-op if an earlier attempt already started it) and then confirmed;</li>
 *   <li>an unconfirmed run older than that is confirmed if Temporal knows its workflow, else closed as
 *       {@code FAILED/START_FAILED};</li>
 *   <li>a confirmed run whose workflow ended without recording an outcome (failed, timed out, cancelled,
 *       terminated, missing) is closed as {@code FAILED} with the reason.</li>
 * </ul>
 *
 * <p>Never marks a run as succeeded: only the workflow does that. Every action is idempotent (start by
 * workflow ID that is never reused, confirmation and closing only of a {@code RUNNING} row), so concurrent
 * passes in several workers are safe without locks, and an import step refuses a run that is no longer running.
 */
@Component
public class SyncRunReconciler {

    static final String START_FAILED = "START_FAILED";

    private static final Logger log = LoggerFactory.getLogger(SyncRunReconciler.class);

    private final WooCommerceImport woo;
    private final StoreAccess storeAccess;
    private final ImportWorkflowStarter starter;
    private final SyncProperties properties;
    private final Clock clock;

    SyncRunReconciler(WooCommerceImport woo, StoreAccess storeAccess, ImportWorkflowStarter starter,
            SyncProperties properties, Clock clock) {
        this.woo = woo;
        this.storeAccess = storeAccess;
        this.starter = starter;
        this.properties = properties;
        this.clock = clock;
    }

    /** One pass over the running runs; a failure on one run does not stop the others. */
    public void reconcile() {
        for (SyncRunDispatch run : woo.runningRuns(properties.batchSize())) {
            try {
                reconcile(run);
            } catch (RuntimeException e) {
                log.warn("Reconciliation of import run {} failed; next pass retries", run.runId(), e);
            }
        }
    }

    private void reconcile(SyncRunDispatch run) {
        // The scope comes from the run record; the store must still exist for the system actor.
        Optional<TrustedStoreScope> scope = storeAccess.systemScope(StoreSync.IMPORT_SERVICE, run.organizationId(),
                run.storeId());
        if (scope.isEmpty()) {
            return;
        }
        TrustedStoreScope system = scope.get();
        if (run.workflowStarted()) {
            closeIfEnded(system, run);
            return;
        }
        Duration age = Duration.between(run.startedAt(), clock.instant());
        if (age.compareTo(properties.startGrace()) < 0) {
            return;
        }
        if (age.compareTo(properties.startDeadline()) < 0) {
            starter.start(run.runId(), system, "sync-reconcile-" + run.runId());
            woo.markWorkflowStarted(system, run.runId());
            return;
        }
        // Too late to start: confirm a workflow that did start, otherwise give the store back.
        if (starter.status(run.runId()).isPresent()) {
            woo.markWorkflowStarted(system, run.runId());
        } else {
            woo.finish(system, run.runId(), SyncRunStatus.FAILED, START_FAILED);
        }
    }

    private void closeIfEnded(TrustedStoreScope system, SyncRunDispatch run) {
        Optional<WorkflowExecutionStatus> status = starter.status(run.runId());
        String reason = status.isEmpty() ? "WORKFLOW_NOT_FOUND" : endedWithoutOutcome(status.get()).orElse(null);
        if (reason != null) {
            woo.finish(system, run.runId(), SyncRunStatus.FAILED, reason);
        }
    }

    /** Empty while the workflow runs. A completed workflow has finished its run unless that step failed. */
    private static Optional<String> endedWithoutOutcome(WorkflowExecutionStatus status) {
        return switch (status) {
            case WORKFLOW_EXECUTION_STATUS_RUNNING, WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW -> Optional.empty();
            case WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> Optional.of("WORKFLOW_TIMED_OUT");
            case WORKFLOW_EXECUTION_STATUS_CANCELED -> Optional.of("WORKFLOW_CANCELED");
            case WORKFLOW_EXECUTION_STATUS_TERMINATED -> Optional.of("WORKFLOW_TERMINATED");
            case WORKFLOW_EXECUTION_STATUS_FAILED -> Optional.of("WORKFLOW_FAILED");
            case WORKFLOW_EXECUTION_STATUS_COMPLETED -> Optional.of("WORKFLOW_ENDED_WITHOUT_OUTCOME");
            default -> Optional.of("WORKFLOW_STATUS_UNKNOWN");
        };
    }
}
