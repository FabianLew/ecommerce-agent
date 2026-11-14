package com.ecommerceagent.automation.inventory;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Long-running workflow of one variant, ID {@code inventory-{storeId}-{variantId}} (ADR-0010). It receives the
 * variant's domain events, ignores repeats and settles uncertain stock with a fresh read. The Inventory
 * Guardian episode (I7) starts from here.
 */
@WorkflowInterface
public interface VariantInventoryWorkflow {

    @WorkflowMethod
    void run(VariantInventoryState state);

    @SignalMethod
    void onEvent(InventorySignal signal);

    @QueryMethod
    VariantInventoryStatus status();

    static String workflowId(java.util.UUID storeId, String variantId) {
        return "inventory-" + storeId + "-" + variantId;
    }
}
