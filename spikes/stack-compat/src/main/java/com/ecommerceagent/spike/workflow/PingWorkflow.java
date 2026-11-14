package com.ecommerceagent.spike.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface PingWorkflow {

    @WorkflowMethod
    String ping(String name);
}
