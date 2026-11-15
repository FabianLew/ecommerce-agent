package com.ecommerceagent.identity;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Data retention (ADR-0022 point 5). Off by default until the periods are approved (O-03); a task without a period
 * never runs.
 */
@ConfigurationProperties("operator.retention")
public record RetentionSettings(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("24h") Duration interval,
        @DefaultValue("1000") int batchSize,
        @DefaultValue("100") int maxBatches,
        Map<String, Duration> periods) {

    public RetentionSettings {
        periods = periods == null ? Map.of() : Map.copyOf(periods);
        if (batchSize < 1 || maxBatches < 1) {
            throw new IllegalArgumentException("operator.retention batch-size and max-batches must be positive");
        }
        periods.forEach((name, period) -> {
            if (period.isNegative() || period.isZero()) {
                throw new IllegalArgumentException("operator.retention.periods." + name + " must be positive");
            }
        });
    }
}
