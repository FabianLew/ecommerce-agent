package com.ecommerceagent.automation.sync;

import java.util.List;

import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.temporal.ActivityErrors;
import com.ecommerceagent.automation.temporal.ActivityStoreScope;
import com.ecommerceagent.integration.WooSourceRefresh;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = TaskQueues.MAIN)
public class StoreSyncActivitiesImpl implements StoreSyncActivities {

    private final WooSourceRefresh refresh;

    StoreSyncActivitiesImpl(WooSourceRefresh refresh) {
        this.refresh = refresh;
    }

    @Override
    public List<String> refreshProducts(List<String> productExternalIds) {
        return ActivityErrors.guarded(() -> refresh.refreshProducts(ActivityStoreScope.current(), productExternalIds));
    }
}
