package com.ecommerceagent.commerce;

import java.time.Instant;
import java.util.Objects;

/**
 * One stock reading from a source. {@code stockQuantity} is null when the source does not count units.
 * {@code reservedQuantity} is present only with {@link ReservationSemantics#SEPARATE_RESERVATIONS}.
 */
public record InventoryReading(Integer stockQuantity, StockStatus stockStatus, ReservationSemantics semantics,
        Integer reservedQuantity, ObservationSource observationSource, Instant sourceUpdatedAt, Instant observedAt) {

    public InventoryReading {
        Objects.requireNonNull(stockStatus, "stockStatus");
        Objects.requireNonNull(semantics, "semantics");
        Objects.requireNonNull(observationSource, "observationSource");
        Objects.requireNonNull(observedAt, "observedAt");
        if (semantics == ReservationSemantics.ALREADY_ACCOUNTED_FOR && reservedQuantity != null) {
            throw new IllegalArgumentException("Reservations are already part of the reported stock");
        }
        if (reservedQuantity != null && reservedQuantity < 0) {
            throw new IllegalArgumentException("reservedQuantity must not be negative");
        }
    }

    /**
     * Units that can still be sold, without subtracting reservations twice (IG-02). Null when the source
     * does not count units, or when separate reservations were not reported.
     */
    public Integer availableToSell() {
        if (stockQuantity == null) {
            return null;
        }
        return switch (semantics) {
            case ALREADY_ACCOUNTED_FOR -> stockQuantity;
            case SEPARATE_RESERVATIONS -> reservedQuantity == null ? null : stockQuantity - reservedQuantity;
        };
    }
}
