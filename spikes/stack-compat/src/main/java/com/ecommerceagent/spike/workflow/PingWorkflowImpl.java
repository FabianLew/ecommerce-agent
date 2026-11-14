package com.ecommerceagent.spike.workflow;

import java.time.Duration;

import io.temporal.activity.ActivityOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

@WorkflowImpl(taskQueues = PingWorkflowImpl.TASK_QUEUE)
public class PingWorkflowImpl implements PingWorkflow {

    public static final String TASK_QUEUE = "spike-ping";

    private final PingActivities activities = Workflow.newActivityStub(
            PingActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

    @Override
    public String ping(String name) {
        return activities.greet(name);
    }
}
