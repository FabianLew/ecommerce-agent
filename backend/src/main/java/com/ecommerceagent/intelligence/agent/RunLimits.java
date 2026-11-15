package com.ecommerceagent.intelligence.agent;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * Limits of one analysis (document 02 §3). They apply to the whole run, repair included; the cost limit is shared by
 * all attempts of one analysis. {@code maxInputTokens} limits the estimated size of one request.
 */
public record RunLimits(int maxModelCalls, int maxToolCalls, Duration maxWallTime, int maxInputTokens,
        int maxOutputTokensTotal, BigDecimal maxEstimatedCostUsd, int maxOutputRepairCalls) {

    public RunLimits {
        Objects.requireNonNull(maxWallTime, "maxWallTime");
        Objects.requireNonNull(maxEstimatedCostUsd, "maxEstimatedCostUsd");
    }
}
