package com.ecommerceagent.automation.inventory;

/** Counters of one workflow run, for diagnostics and tests. */
public record VariantInventoryStatus(int handled, int duplicates, int verifications, boolean verificationPending,
        String latestSourceUpdatedAt) {
}
