package com.ecommerceagent.automation.sync;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** One scheduled run of polling or order reconciliation for a connection (B-16, ADR-0010). */
@WorkflowInterface
public interface IncrementalSyncWorkflow {

    /** {@code kind} is {@code POLL} or {@code RECONCILE}; returns the run status. */
    @WorkflowMethod
    String run(String connectionId, String kind);
}
