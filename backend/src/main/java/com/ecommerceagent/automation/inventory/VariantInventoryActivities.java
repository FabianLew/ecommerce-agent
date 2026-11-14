package com.ecommerceagent.automation.inventory;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface VariantInventoryActivities {

    /** Reads the variant's stock from the source and records it; returns the outcome code. */
    String verify(String variantId);

    /** Assesses inventory risk from the stored stock (after a stock change event). */
    RiskCheck assessRisk(String variantId);

    /** Reads the variant's stock from the source, then assesses risk with the stock confirmed by that read. */
    RiskCheck verifyAndAssess(String variantId);
}
