package com.ecommerceagent.commerce;

import java.time.Instant;
import java.util.UUID;

public record InventoryObservation(UUID id, UUID variantId, InventoryReading reading, Instant ingestedAt) {
}
