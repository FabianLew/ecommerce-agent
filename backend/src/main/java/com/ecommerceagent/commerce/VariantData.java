package com.ecommerceagent.commerce;

import java.time.Instant;
import java.util.Objects;

/** Sellable unit as read from a source: a variation, or the product itself for simple products. */
public record VariantData(String externalId, String productExternalId, String name, String sku, String status,
        StockTracking stockTracking, Backorders backorders, Instant sourceUpdatedAt) {

    public VariantData {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(productExternalId, "productExternalId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(stockTracking, "stockTracking");
        Objects.requireNonNull(backorders, "backorders");
    }
}
