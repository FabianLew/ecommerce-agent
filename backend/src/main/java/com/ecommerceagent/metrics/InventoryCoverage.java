package com.ecommerceagent.metrics;

import java.time.Instant;
import java.util.UUID;

import com.ecommerceagent.commerce.InventoryObservation;

/**
 * Inputs of the Inventory Guardian baseline forecast for one variant (03 §2, 03 §4 "forecast"), all
 * calculated by code. {@code stock} is the reading used for days of cover, or null when there was none.
 */
public record InventoryCoverage(
        UUID variantId,
        Instant asOf,
        MetricValue unitsSold,
        MetricValue velocityPerDay,
        MetricValue daysOfCover,
        InventoryObservation stock) {
}
