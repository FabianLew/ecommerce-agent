package com.ecommerceagent.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import com.ecommerceagent.metrics.MetricQuality;
import com.ecommerceagent.metrics.MetricReason;
import com.ecommerceagent.metrics.MetricValue;
import io.swagger.v3.oas.annotations.media.Schema;

/** The shared MetricDTO of document 04 §3 (ADR-0019). Values are decimal strings; null means unavailable. */
record MetricDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String key,
        @Schema(description = "Decimal string; null when quality is UNAVAILABLE") String value,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String unit,
        String currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MetricQuality quality,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PeriodDto period,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String definitionVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> sourceRefs,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant observedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Why the value is missing or limited")
        List<MetricReason> missingComponents,
        ComparisonDto comparison) {

    record PeriodDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant from,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant toExclusive,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String timezone) {
    }

    /** The base period value, the absolute and the percentage difference; a zero or missing base has no percent. */
    record ComparisonDto(
            String baseValue,
            String absoluteDifference,
            String percentDifference,
            @Schema(description = "Why there is no percentage") String percentUnavailableReason) {

        static ComparisonDto of(MetricValue current, MetricValue base) {
            if (base == null) {
                return null;
            }
            if (current.value() == null || base.value() == null) {
                return new ComparisonDto(text(base.value()), null, null, "VALUE_UNAVAILABLE");
            }
            BigDecimal difference = current.value().subtract(base.value());
            if (base.value().signum() == 0) {
                return new ComparisonDto(text(base.value()), text(difference), null, "ZERO_BASE");
            }
            BigDecimal percent = difference.multiply(BigDecimal.valueOf(100))
                    .divide(base.value().abs(), 1, RoundingMode.HALF_UP);
            return new ComparisonDto(text(base.value()), text(difference), percent.toPlainString(), null);
        }
    }

    static MetricDto of(MetricValue value, String currency, List<String> sourceRefs, MetricValue base) {
        List<MetricReason> missing = value.unavailableReason() != null ? List.of(value.unavailableReason())
                : value.limitations();
        return new MetricDto(value.definition().key(), text(value.value()), value.definition().unit(),
                "currency".equals(value.definition().unit()) ? currency : null, value.quality(),
                new PeriodDto(value.period().from(), value.period().toExclusive(), value.period().timezone()),
                value.definitionVersion(), sourceRefs, value.period().asOf(), missing,
                ComparisonDto.of(value, base));
    }

    static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
