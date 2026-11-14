package com.ecommerceagent.automation.diagnostics;

import java.time.Duration;

import com.ecommerceagent.automation.ScopeCheckResult;
import com.ecommerceagent.automation.TaskQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

@WorkflowImpl(taskQueues = TaskQueues.MAIN)
public class ScopeCheckWorkflowImpl implements ScopeCheckWorkflow {

    private final ScopeCheckActivities activities = Workflow.newActivityStub(ScopeCheckActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    @Override
    public ScopeCheckResult check() {
        return activities.verifiedScope();
    }
}
