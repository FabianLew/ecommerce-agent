package com.ecommerceagent.metrics;

import java.util.List;

/**
 * Versioned metric definitions (01 §6: every metric has a definition, period and calculation version).
 * A material change of a formula, window or qualifying statuses requires a new version, not an edit.
 */
public enum MetricDefinition {

    /** Units of a variant in qualifying orders placed in the window (by {@code placed_at}). */
    UNITS_SOLD("units_sold", 1, "units"),

    /** UNITS_SOLD over the last 7 full local days of the store, divided by 7. */
    SALES_VELOCITY_7_FULL_LOCAL_DAYS("sales_velocity_7_full_local_days", 1, "units/day"),

    /** Available-to-sell stock divided by SALES_VELOCITY_7_FULL_LOCAL_DAYS v1. */
    DAYS_OF_COVER("days_of_cover", 1, "days");

    /**
     * Order statuses counted as demand in v1. Stock already reflects these statuses in WooCommerce
     * (ADR-0008). Store-specific statuses come with the Store Business Profile.
     */
    public static final List<String> QUALIFYING_ORDER_STATUSES_V1 = List.of("processing", "completed", "on-hold");

    /** Length of the sales window in full local days. */
    public static final int VELOCITY_WINDOW_DAYS = 7;

    /** Decimal places of ratios; half-up rounding, as in the IG-01 example (2.5714). */
    public static final int SCALE = 4;

    private final String key;
    private final int version;
    private final String unit;

    MetricDefinition(String key, int version, String unit) {
        this.key = key;
        this.version = version;
        this.unit = unit;
    }

    public String key() {
        return key;
    }

    public int version() {
        return version;
    }

    public String unit() {
        return unit;
    }
}
