package com.ecommerceagent.intelligence.agent;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Limits of one analysis (document 02 §3, ADR-0027 section 5), taken from a versioned run policy whose reference
 * {@code policy} every run records. They apply to the whole run, repair included; the cost limit is shared by all
 * attempts of one analysis. {@code maxInputTokens} limits the estimated size of one request and
 * {@code maxOutputTokensPerCall} the output of one call; neither replaces the limits of the run.
 * {@code maxConcurrentRunsPerStore} bounds the store's active runs of this agent (ADR-0027 section 6.2).
 */
public record RunLimits(String policy, int maxModelCalls, int maxToolCalls, Duration maxWallTime, int maxInputTokens,
        int maxOutputTokensPerCall, int maxOutputTokensTotal, BigDecimal maxEstimatedCostUsd,
        int maxOutputRepairCalls, int maxConcurrentRunsPerStore) {

    public RunLimits {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(maxWallTime, "maxWallTime");
        Objects.requireNonNull(maxEstimatedCostUsd, "maxEstimatedCostUsd");
        if (maxModelCalls < 1 || maxToolCalls < 0 || maxInputTokens < 1 || maxOutputTokensPerCall < 1
                || maxOutputRepairCalls < 0 || maxConcurrentRunsPerStore < 1) {
            throw new IllegalArgumentException("Run policy " + policy + " has a limit out of range");
        }
        if (maxOutputTokensTotal < maxOutputTokensPerCall) {
            throw new IllegalArgumentException("Run policy " + policy
                    + ": the output limit of the run must not be below the output limit of one call");
        }
        if (maxWallTime.isNegative() || maxWallTime.isZero()) {
            throw new IllegalArgumentException("Run policy " + policy + ": the wall time must be positive");
        }
        if (maxEstimatedCostUsd.signum() <= 0) {
            throw new IllegalArgumentException("Run policy " + policy + ": the cost limit must be positive");
        }
    }

    /** The limits as every run records them ({@code agent_run.run_limits}). */
    public Map<String, String> recorded() {
        Map<String, String> recorded = new LinkedHashMap<>();
        recorded.put("policy", policy);
        recorded.put("maxModelCalls", Integer.toString(maxModelCalls));
        recorded.put("maxToolCalls", Integer.toString(maxToolCalls));
        recorded.put("maxWallTime", maxWallTime.toString());
        recorded.put("maxInputTokens", Integer.toString(maxInputTokens));
        recorded.put("maxOutputTokensPerCall", Integer.toString(maxOutputTokensPerCall));
        recorded.put("maxOutputTokensTotal", Integer.toString(maxOutputTokensTotal));
        recorded.put("maxEstimatedCostUsd", maxEstimatedCostUsd.stripTrailingZeros().toPlainString());
        recorded.put("maxOutputRepairCalls", Integer.toString(maxOutputRepairCalls));
        recorded.put("maxConcurrentRunsPerStore", Integer.toString(maxConcurrentRunsPerStore));
        return recorded;
    }
}
