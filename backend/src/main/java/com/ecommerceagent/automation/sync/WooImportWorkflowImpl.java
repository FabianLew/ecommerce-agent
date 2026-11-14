package com.ecommerceagent.automation.sync;

import java.time.Duration;

import com.ecommerceagent.automation.TaskQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

@WorkflowImpl(taskQueues = TaskQueues.MAIN)
public class WooImportWorkflowImpl implements WooImportWorkflow {

    static final String ORDER_COUNT_MISMATCH = "ORDER_COUNT_MISMATCH";

    private final WooImportActivities steps = Workflow.newActivityStub(WooImportActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(15))
                    .setHeartbeatTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(5))
                            .setBackoffCoefficient(3)
                            .setMaximumAttempts(4)
                            .build())
                    .build());

    private final WooImportActivities bookkeeping = Workflow.newActivityStub(WooImportActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(10).build())
                    .build());

    @Override
    public String run(String runId) {
        try {
            steps.importCatalog(runId);
            boolean complete = steps.importOrders(runId);
            String status = complete ? "SUCCEEDED" : "INCOMPLETE";
            bookkeeping.finish(runId, status, complete ? null : ORDER_COUNT_MISMATCH);
            return status;
        } catch (ActivityFailure failure) {
            String code = failure.getCause() instanceof ApplicationFailure application
                    ? application.getType() : "UNEXPECTED";
            bookkeeping.finish(runId, "FAILED", code);
            return "FAILED";
        }
    }
}
