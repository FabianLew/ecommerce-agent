package com.ecommerceagent.automation.sync;

import io.temporal.activity.ActivityInterface;

/** Import steps; each reads the store only from the verified activity scope, never from arguments. */
@ActivityInterface
public interface WooImportActivities {

    void importCatalog(String runId);

    /** Returns true when the imported orders match the total reported by WooCommerce. */
    boolean importOrders(String runId);

    void finish(String runId, String status, String errorCode);
}
