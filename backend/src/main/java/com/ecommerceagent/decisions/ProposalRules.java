package com.ecommerceagent.decisions;

import java.time.Duration;
import java.util.Set;

/**
 * The closed lists a process allows in a proposal (ADR-0012, domain layer): action types, reason codes, missing
 * requirements and monitor conditions, and how far ahead a monitor review may be.
 */
public record ProposalRules(Set<String> actionTypes, Set<String> reasonCodes, Set<String> missingRequirements,
        Set<String> monitorConditions, Duration minReviewDelay, Duration maxReviewDelay) {

    /** Inventory Guardian (03 §4–8). */
    public static final ProposalRules INVENTORY_RISK = new ProposalRules(
            Set.of(PauseAd.TYPE),
            Set.of("COVER_BELOW_THRESHOLD", "COVER_NEAR_THRESHOLD", "COVER_ABOVE_THRESHOLD", "STOCK_NOT_CURRENT",
                    "FORECAST_UNAVAILABLE", "REPLENISHMENT_UNKNOWN", "REPLENISHMENT_EXPECTED", "NO_MAPPED_ADS",
                    "DEDICATED_AD_ACTIVE", "MULTI_PRODUCT_AD_EXCLUDED", "ONLY_MULTI_PRODUCT_ADS", "MAPPING_UNVERIFIED",
                    "CREATIVE_CHANGED", "AD_NOT_ACTIVE", "AD_STATE_NOT_CURRENT"),
            Set.of("CURRENT_STOCK", "SALES_HISTORY_COMPLETE", "AD_STATE_CURRENT", "MAPPING_VERIFIED"),
            Set.of("COVER_BELOW_THRESHOLD", "COVER_NEAR_THRESHOLD", "STOCK_CHANGED", "AD_STATE_CHANGED"),
            Duration.ofMinutes(15), Duration.ofDays(7));

    public static ProposalRules forCategory(OpportunityCategory category) {
        return switch (category) {
            case INVENTORY_RISK -> INVENTORY_RISK;
        };
    }
}
