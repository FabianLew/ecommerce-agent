package com.ecommerceagent.aigovernance;

import java.util.Map;
import java.util.Objects;

/**
 * What the provider reported for one call: its request id and the units it charges for. No prompt or response
 * content. {@code extraUnits} are priced units beyond tokens (for example web searches), by catalog key.
 */
public record ProviderUsage(String providerCallId, long inputTokens, long outputTokens, long cacheWriteTokens,
        long cacheReadTokens, Map<String, Long> extraUnits) {

    public ProviderUsage {
        if (providerCallId != null && (providerCallId.isBlank() || providerCallId.length() > 200)) {
            throw new IllegalArgumentException("providerCallId must be 1 to 200 characters");
        }
        if (inputTokens < 0 || outputTokens < 0 || cacheWriteTokens < 0 || cacheReadTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        extraUnits = Map.copyOf(Objects.requireNonNullElse(extraUnits, Map.of()));
        if (extraUnits.values().stream().anyMatch(units -> units < 0)) {
            throw new IllegalArgumentException("extra units must not be negative");
        }
    }

    public static ProviderUsage tokens(String providerCallId, long inputTokens, long outputTokens) {
        return new ProviderUsage(providerCallId, inputTokens, outputTokens, 0, 0, Map.of());
    }
}
