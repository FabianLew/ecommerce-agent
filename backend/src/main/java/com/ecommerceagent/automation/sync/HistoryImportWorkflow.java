package com.ecommerceagent.automation.sync;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** One scheduled run of the order history import of a connection (B-13b, ADR-0015). */
@WorkflowInterface
public interface HistoryImportWorkflow {

    /** Imports up to {@code maxChunks} chunks, {@code pauseSeconds} apart; returns the last step's outcome. */
    @WorkflowMethod
    String run(String connectionId, int maxChunks, int pauseSeconds);
}
