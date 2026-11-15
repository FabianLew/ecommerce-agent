package com.ecommerceagent.automation.execution;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the executor (ADR-0020 points 3 and 5). The pause limit and the cooldown are policy parameters
 * (ADR-0026 point 6): the executor reads them from the evaluation it was authorised under. Unknown keys are refused,
 * so a stale {@code operator.execution.max-pauses-per-store-24h} or {@code cooldown-per-ad} stops the start instead
 * of being ignored.
 */
@ConfigurationProperties(prefix = "operator.execution", ignoreUnknownFields = false)
public record ExecutionSettings(
        @DefaultValue("true") boolean mutationsEnabled,
        @DefaultValue("60s") Duration authorizationTtl,
        @DefaultValue("2m") Duration leaseDuration) {

    public static final Duration LIMIT_WINDOW = Duration.ofHours(24);

    public ExecutionSettings {
        if (authorizationTtl == null || authorizationTtl.isNegative() || authorizationTtl.isZero()) {
            throw new IllegalArgumentException(
                    "operator.execution.authorization-ttl must be positive, was " + authorizationTtl);
        }
    }
}
