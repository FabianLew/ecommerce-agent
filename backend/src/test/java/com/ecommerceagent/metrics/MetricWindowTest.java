package com.ecommerceagent.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.ecommerceagent.metrics.MetricValue.MetricPeriod;
import org.junit.jupiter.api.Test;

class MetricWindowTest {

    static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @Test
    void windowIsSevenFullLocalDaysBeforeToday() {
        MetricPeriod window = InventoryMetrics.velocityWindow(Instant.parse("2026-11-14T08:00:30Z"), WARSAW);

        assertThat(window.from()).isEqualTo(Instant.parse("2026-11-06T23:00:00Z"));
        assertThat(window.toExclusive()).isEqualTo(Instant.parse("2026-11-13T23:00:00Z"));
        assertThat(window.timezone()).isEqualTo("Europe/Warsaw");
    }

    @Test
    void windowAcrossDaylightSavingChangeHasSevenLocalDays() {
        // 25.10.2026: clocks go back in Poland, so this window is 7 local days but 169 hours long.
        MetricPeriod window = InventoryMetrics.velocityWindow(Instant.parse("2026-10-30T10:00:00Z"), WARSAW);

        assertThat(window.from()).isEqualTo(Instant.parse("2026-10-22T22:00:00Z"));
        assertThat(window.toExclusive()).isEqualTo(Instant.parse("2026-10-29T23:00:00Z"));
        assertThat(Duration.between(window.from(), window.toExclusive())).isEqualTo(Duration.ofHours(169));
    }

    @Test
    void localMidnightNotUtcMidnightStartsTheDay() {
        // 23:30 UTC on 13.11 is already 14.11 in Warsaw.
        MetricPeriod window = InventoryMetrics.velocityWindow(Instant.parse("2026-11-13T23:30:00Z"), WARSAW);

        assertThat(window.toExclusive()).isEqualTo(Instant.parse("2026-11-13T23:00:00Z"));
    }

    @Test
    void unavailableValueIsAlwaysNullWithAReason() {
        MetricPeriod period = new MetricPeriod(Instant.EPOCH, Instant.EPOCH, "UTC", Instant.EPOCH);

        assertThatThrownBy(() -> new MetricValue(MetricDefinition.DAYS_OF_COVER, BigDecimal.ZERO,
                MetricQuality.UNAVAILABLE, period, MetricReason.NO_SALES_IN_WINDOW, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricValue(MetricDefinition.DAYS_OF_COVER, null, MetricQuality.UNAVAILABLE,
                period, null, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricValue(MetricDefinition.DAYS_OF_COVER, null, MetricQuality.ESTIMATED,
                period, MetricReason.NO_SALES_IN_WINDOW, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
