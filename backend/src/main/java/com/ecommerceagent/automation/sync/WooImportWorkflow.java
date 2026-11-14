package com.ecommerceagent.automation.sync;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Initial WooCommerce import of one run (B-13, ADR-0008): catalog, then orders, then the run's outcome. */
@WorkflowInterface
public interface WooImportWorkflow {

    /** Returns the final status name of the run. */
    @WorkflowMethod
    String run(String runId);
}
