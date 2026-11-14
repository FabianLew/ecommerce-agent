package com.ecommerceagent.metrics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One calculated metric (04 §3 MetricDTO). {@code value} is null exactly when {@code quality} is UNAVAILABLE,
 * and then {@code unavailableReason} says why. {@code limitations} qualify a value that exists.
 */
public record MetricValue(
        MetricDefinition definition,
        BigDecimal value,
        MetricQuality quality,
        MetricPeriod period,
        MetricReason unavailableReason,
        List<MetricReason> limitations) {

    public MetricValue {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(period, "period");
        limitations = List.copyOf(limitations);
        if ((value == null) != (quality == MetricQuality.UNAVAILABLE)) {
            throw new IllegalArgumentException("value is null exactly when quality is UNAVAILABLE");
        }
        if ((value == null) != (unavailableReason != null)) {
            throw new IllegalArgumentException("an unavailable metric needs a reason, an available one none");
        }
    }

    static MetricValue of(MetricDefinition definition, BigDecimal value, MetricQuality quality, MetricPeriod period,
            List<MetricReason> limitations) {
        return new MetricValue(definition, value, quality, period, null, limitations);
    }

    static MetricValue unavailable(MetricDefinition definition, MetricPeriod period, MetricReason reason) {
        return new MetricValue(definition, null, MetricQuality.UNAVAILABLE, period, reason, List.of());
    }

    public String definitionVersion() {
        return definition.key() + "@v" + definition.version();
    }

    /** The window the value describes: [from, toExclusive) in UTC, with the store timezone used for its days. */
    public record MetricPeriod(Instant from, Instant toExclusive, String timezone, Instant asOf) {
    }
}
