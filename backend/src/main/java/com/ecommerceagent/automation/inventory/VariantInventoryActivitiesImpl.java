package com.ecommerceagent.automation.inventory;

import java.util.UUID;

import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.temporal.ActivityErrors;
import com.ecommerceagent.automation.temporal.ActivityStoreScope;
import com.ecommerceagent.integration.WooSourceRefresh;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = TaskQueues.MAIN)
public class VariantInventoryActivitiesImpl implements VariantInventoryActivities {

    private final WooSourceRefresh refresh;

    VariantInventoryActivitiesImpl(WooSourceRefresh refresh) {
        this.refresh = refresh;
    }

    @Override
    public String verify(String variantId) {
        return ActivityErrors.guarded(() -> refresh.refreshVariant(ActivityStoreScope.current(),
                UUID.fromString(variantId)));
    }
}
