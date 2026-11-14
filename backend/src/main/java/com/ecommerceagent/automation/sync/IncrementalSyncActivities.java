package com.ecommerceagent.automation.sync;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface IncrementalSyncActivities {

    String poll(String connectionId);

    String reconcileOrders(String connectionId);
}
