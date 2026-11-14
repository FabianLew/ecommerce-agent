package com.ecommerceagent.automation.inventory;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface VariantInventoryActivities {

    /** Reads the variant's stock from the source and records it; returns the outcome code. */
    String verify(String variantId);
}
