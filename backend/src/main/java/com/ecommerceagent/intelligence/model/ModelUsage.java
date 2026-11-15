package com.ecommerceagent.intelligence.model;

/** Tokens a provider reported for one call; null when it did not report them. */
public record ModelUsage(Integer inputTokens, Integer outputTokens) {

    public static final ModelUsage UNKNOWN = new ModelUsage(null, null);

    public boolean known() {
        return inputTokens != null && outputTokens != null;
    }
}
