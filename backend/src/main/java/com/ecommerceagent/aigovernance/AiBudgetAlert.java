package com.ecommerceagent.aigovernance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A budget that reached a threshold of its limit in one period (ADR-0027 section 14). Amounts are in the budget's
 * currency, the technical settlement currency (USD), and are shown with that currency, never converted.
 */
public record AiBudgetAlert(UUID id, String level, String scopeKey, UUID organizationId, UUID storeId,
        String periodType, Instant periodStart, Instant periodEnd, int thresholdPercent, BigDecimal amountLimit,
        BigDecimal usedAmount, String currency, Instant createdAt) {
}
