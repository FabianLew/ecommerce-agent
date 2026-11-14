package com.ecommerceagent.commerce;

import java.time.Instant;
import java.util.Objects;

/** Product as read from a source. The store comes from the scope, never from this payload. */
public record ProductData(String externalId, String name, String sku, String productType, String status,
        Instant sourceUpdatedAt) {

    public ProductData {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(productType, "productType");
        Objects.requireNonNull(status, "status");
    }
}
