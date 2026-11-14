package com.ecommerceagent.automation.sync;

import java.util.List;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface StoreSyncActivities {

    List<String> refreshProducts(List<String> productExternalIds);
}
