package com.ecommerceagent.automation.sync;

import java.time.Duration;

import com.ecommerceagent.automation.TaskQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/**
 * Stops at the first step that does not move the history back: a mismatch or a source error is read again by
 * the next scheduled run, so the schedule interval is the backoff. Retries here cover only our own outages.
 */
@WorkflowImpl(taskQueues = TaskQueues.MAIN)
public class HistoryImportWorkflowImpl implements HistoryImportWorkflow {

    private final HistoryImportActivities activities = Workflow.newActivityStub(HistoryImportActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    .setHeartbeatTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(10))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public String run(String connectionId, int maxChunks, int pauseSeconds) {
        String outcome = "NOT_STARTED";
        for (int chunk = 0; chunk < maxChunks; chunk++) {
            if (chunk > 0 && pauseSeconds > 0) {
                Workflow.sleep(Duration.ofSeconds(pauseSeconds));
            }
            outcome = activities.importNext(connectionId);
            if (!"IMPORTED".equals(outcome)) {
                break;
            }
        }
        return outcome;
    }
}
