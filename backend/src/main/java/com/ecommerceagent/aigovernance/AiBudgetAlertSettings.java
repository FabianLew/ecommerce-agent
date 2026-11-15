package com.ecommerceagent.aigovernance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * Parameter set {@code ai-budget-alerts} (ADR-0027 section 14): the percents of a budget's limit at which platform
 * operators are alerted, once per threshold and budget period, for every budget level. Defaults 70, 90 and 100. A
 * changed default needs a new version ({@code ParameterSetVersionTest}); the cloud profile refuses an override that
 * keeps the default version; each recorded alert keeps the version that produced it.
 */
@ConfigurationProperties(AiBudgetAlertSettings.PREFIX)
public record AiBudgetAlertSettings(
        @DefaultValue(DEFAULT_VERSION) String version,
        @DefaultValue({"70", "90", "100"}) List<Integer> thresholdPercents) {

    public static final String DEFAULT_VERSION = "ai-budget-alerts/1";

    public static final String PREFIX = "operator.ai-budget-alerts";

    public AiBudgetAlertSettings {
        if (version == null || !version.matches("ai-budget-alerts/[0-9]+")) {
            throw new IllegalArgumentException(PREFIX + ".version must look like ai-budget-alerts/N, was " + version);
        }
        if (thresholdPercents == null || thresholdPercents.isEmpty() || thresholdPercents.size() > 10
                || thresholdPercents.stream().anyMatch(t -> t == null || t < 1 || t > 100)) {
            throw new IllegalArgumentException(PREFIX + ".threshold-percents must be 1 to 10 values from 1 to 100");
        }
        thresholdPercents = thresholdPercents.stream().distinct().sorted().toList();
    }

    public static AiBudgetAlertSettings defaults() {
        return new Binder(List.of()).bindOrCreate(PREFIX, AiBudgetAlertSettings.class);
    }

    /** The parameters in a fixed order. */
    public Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("version", version);
        parameters.put("thresholdPercents",
                thresholdPercents.stream().map(String::valueOf).collect(Collectors.joining(",")));
        return parameters;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiBudgetAlertSettings.class)
    static class Registration {
    }
}
