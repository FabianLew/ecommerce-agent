package com.ecommerceagent.automation.sync;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Long-running workflow of one store connection, ID {@code store-sync-{connectionId}} (ADR-0010): re-reads
 * products that events referred to but the catalog did not know, collecting requests for a short delay.
 */
@WorkflowInterface
public interface StoreSyncWorkflow {

    @WorkflowMethod
    void run(StoreSyncState state);

    @SignalMethod
    void refreshProduct(CatalogSignal signal);

    /** Number of products read so far in this run. */
    @QueryMethod
    int refreshed();

    static String workflowId(java.util.UUID connectionId) {
        return "store-sync-" + connectionId;
    }
}
