package com.ecommerceagent.automation.sync;

import java.time.Duration;

import com.ecommerceagent.automation.TaskQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

/** Errors from the shop are recorded by the activity itself; retries here cover only our own outages. */
@WorkflowImpl(taskQueues = TaskQueues.MAIN)
public class IncrementalSyncWorkflowImpl implements IncrementalSyncWorkflow {

    private final IncrementalSyncActivities activities = Workflow.newActivityStub(IncrementalSyncActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(4))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(10))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public String run(String connectionId, String kind) {
        return "RECONCILE".equals(kind) ? activities.reconcileOrders(connectionId) : activities.poll(connectionId);
    }
}
