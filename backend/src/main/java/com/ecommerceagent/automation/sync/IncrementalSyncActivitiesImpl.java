package com.ecommerceagent.automation.sync;

import java.util.UUID;

import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.temporal.ActivityErrors;
import com.ecommerceagent.automation.temporal.ActivityStoreScope;
import com.ecommerceagent.integration.WooIncrementalSync;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = TaskQueues.MAIN)
public class IncrementalSyncActivitiesImpl implements IncrementalSyncActivities {

    private final WooIncrementalSync sync;

    IncrementalSyncActivitiesImpl(WooIncrementalSync sync) {
        this.sync = sync;
    }

    @Override
    public String poll(String connectionId) {
        return ActivityErrors.guarded(() -> sync.poll(ActivityStoreScope.current(), UUID.fromString(connectionId))
                .status().name());
    }

    @Override
    public String reconcileOrders(String connectionId) {
        return ActivityErrors.guarded(() -> sync.reconcileOrders(ActivityStoreScope.current(),
                UUID.fromString(connectionId)).status().name());
    }
}
