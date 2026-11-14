package com.ecommerceagent.commerce;

import java.util.UUID;

public record Variant(UUID id, UUID productId, CommerceSource source, String externalId, String productName,
        String name, String sku, String status, StockTracking stockTracking, Backorders backorders) {
}
