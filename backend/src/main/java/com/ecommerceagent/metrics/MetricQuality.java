package com.ecommerceagent.metrics;

/** Data quality from the product data model (01 §6); {@code UNAVAILABLE} always comes with a null value. */
public enum MetricQuality {
    CONFIRMED,
    ESTIMATED,
    UNAVAILABLE
}
