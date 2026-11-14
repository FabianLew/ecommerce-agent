package com.ecommerceagent.automation.inventory;

import java.util.List;

/**
 * Input of {@link VariantInventoryWorkflow}, carried over {@code continueAsNew}. Delays are set by the
 * dispatcher from configuration, so workflow code stays deterministic.
 */
public record VariantInventoryState(String variantId, List<String> recentEventIds, String latestSourceUpdatedAt,
        long verifyDelaySeconds, long orderDelaySeconds) {

    public VariantInventoryState {
        recentEventIds = recentEventIds == null ? List.of() : List.copyOf(recentEventIds);
    }
}
