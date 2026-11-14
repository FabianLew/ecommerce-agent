package com.ecommerceagent.automation.guardian;

/** Input of {@link InventoryGuardianWorkflow}: the episode and the limits it runs with. */
public record GuardianInput(String opportunityId, String variantId, long proposalTtlSeconds,
        long episodeTimeoutSeconds, long dataRetryDelaySeconds, int maxDataRetries, int maxReanalyses,
        String significantCoverChange) {
}
