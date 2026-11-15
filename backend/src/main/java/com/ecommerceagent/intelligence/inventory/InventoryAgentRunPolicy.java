package com.ecommerceagent.intelligence.inventory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ecommerceagent.intelligence.agent.RunLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * Run policy {@code inventory-agent-run} of the Inventory Agent (ADR-0027 section 5, gap 5): the limits of one
 * analysis as a versioned parameter set (ADR-0026 point 6). The backend sets them; the model never sees or changes
 * them. Every run records the version and the limits. A changed default needs a new version
 * ({@code ParameterSetVersionTest}), and the cloud profile refuses an override that keeps the default version.
 * {@code maxAttempts} bounds the attempts of one analysis along the model policy (primary, then fallback); all of
 * them share {@code maxRunCostUsd}. The policy is read at start-up, so a change takes effect with the next start,
 * before which every running run has ended (a run lost by a stopped worker counts as a used attempt).
 */
@ConfigurationProperties(InventoryAgentRunPolicy.PREFIX)
public record InventoryAgentRunPolicy(
        @DefaultValue(DEFAULT_VERSION) String version,
        @DefaultValue("5") int maxModelCalls,
        @DefaultValue("8") int maxToolCalls,
        @DefaultValue("60s") Duration maxRunDuration,
        @DefaultValue("12000") int maxInputTokensPerCall,
        @DefaultValue("1500") int maxOutputTokensPerCall,
        @DefaultValue("4000") int maxOutputTokensPerRun,
        @DefaultValue("0.10") BigDecimal maxRunCostUsd,
        @DefaultValue("1") int maxRepairAttempts,
        @DefaultValue("2") int maxAttempts) {

    public static final String DEFAULT_VERSION = "inventory-agent-run/1";

    public static final String PREFIX = "operator.agent.run-policies.inventory-agent";

    public InventoryAgentRunPolicy {
        if (version == null || !version.matches("inventory-agent-run/[0-9]+")) {
            throw new IllegalArgumentException(PREFIX + ".version must look like inventory-agent-run/N, was "
                    + version);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(PREFIX + ".max-attempts must be at least 1");
        }
        limits(version, maxModelCalls, maxToolCalls, maxRunDuration, maxInputTokensPerCall, maxOutputTokensPerCall,
                maxOutputTokensPerRun, maxRunCostUsd, maxRepairAttempts);
    }

    /** The defaults of the current version, as bound without any configuration. */
    public static InventoryAgentRunPolicy defaults() {
        return new Binder(List.of()).bindOrCreate(PREFIX, InventoryAgentRunPolicy.class);
    }

    /** The limits the runtime applies, with this policy's version. */
    public RunLimits limits() {
        return limits(version, maxModelCalls, maxToolCalls, maxRunDuration, maxInputTokensPerCall,
                maxOutputTokensPerCall, maxOutputTokensPerRun, maxRunCostUsd, maxRepairAttempts);
    }

    /** The parameters as recorded with every run, in a fixed order. */
    public Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("version", version);
        parameters.put("maxModelCalls", Integer.toString(maxModelCalls));
        parameters.put("maxToolCalls", Integer.toString(maxToolCalls));
        parameters.put("maxRunDuration", maxRunDuration.toString());
        parameters.put("maxInputTokensPerCall", Integer.toString(maxInputTokensPerCall));
        parameters.put("maxOutputTokensPerCall", Integer.toString(maxOutputTokensPerCall));
        parameters.put("maxOutputTokensPerRun", Integer.toString(maxOutputTokensPerRun));
        parameters.put("maxRunCostUsd", maxRunCostUsd.stripTrailingZeros().toPlainString());
        parameters.put("maxRepairAttempts", Integer.toString(maxRepairAttempts));
        parameters.put("maxAttempts", Integer.toString(maxAttempts));
        return parameters;
    }

    private static RunLimits limits(String version, int maxModelCalls, int maxToolCalls, Duration maxRunDuration,
            int maxInputTokensPerCall, int maxOutputTokensPerCall, int maxOutputTokensPerRun, BigDecimal maxRunCostUsd,
            int maxRepairAttempts) {
        if (maxRunDuration == null || maxRunCostUsd == null) {
            throw new IllegalArgumentException(PREFIX + ": max-run-duration and max-run-cost-usd are required");
        }
        try {
            return new RunLimits(version, maxModelCalls, maxToolCalls, maxRunDuration, maxInputTokensPerCall,
                    maxOutputTokensPerCall, maxOutputTokensPerRun, maxRunCostUsd, maxRepairAttempts);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(PREFIX + ": " + e.getMessage(), e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InventoryAgentRunPolicy.class)
    static class Registration {
    }
}
