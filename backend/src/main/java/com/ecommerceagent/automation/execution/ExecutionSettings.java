package com.ecommerceagent.automation.execution;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Limits of the executor (ADR-0020 points 3, 5 and 6; document 03 §7 sample parameters). */
@ConfigurationProperties("operator.execution")
public record ExecutionSettings(
        @DefaultValue("true") boolean mutationsEnabled,
        @DefaultValue("60s") Duration authorizationTtl,
        @DefaultValue("2m") Duration leaseDuration,
        @DefaultValue("3") int maxPausesPerStore24h,
        @DefaultValue("24h") Duration cooldownPerAd) {

    public static final Duration LIMIT_WINDOW = Duration.ofHours(24);
}
