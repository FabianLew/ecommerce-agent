package com.ecommerceagent.aigovernance;

import java.util.Map;
import java.util.Objects;

/**
 * What one model call may consume at most, as the backend sets it: the provider, model and region, the estimated
 * input tokens (before the safety margin), the call's output token limit, whether prompt caching may write to the
 * cache, and the units of any additional paid feature (for example {@code web_search} requests). The model never
 * supplies any of it.
 */
public record AiCallEstimate(String provider, String model, String region, long estimatedInputTokens,
        long maxOutputTokens, boolean promptCaching, Map<String, Long> extraUnits) {

    public AiCallEstimate {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(region, "region");
        if (estimatedInputTokens <= 0 || maxOutputTokens <= 0) {
            throw new IllegalArgumentException("Token limits must be positive");
        }
        extraUnits = Map.copyOf(extraUnits);
        extraUnits.forEach((unit, count) -> {
            if (count == null || count < 0) {
                throw new IllegalArgumentException("Negative units of " + unit);
            }
        });
    }

    public static AiCallEstimate of(String provider, String model, String region, long estimatedInputTokens,
            long maxOutputTokens) {
        return new AiCallEstimate(provider, model, region, estimatedInputTokens, maxOutputTokens, false, Map.of());
    }
}
