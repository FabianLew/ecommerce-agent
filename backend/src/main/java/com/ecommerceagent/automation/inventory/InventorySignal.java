package com.ecommerceagent.automation.inventory;

/**
 * One domain event delivered to a variant's workflow (ADR-0010). {@code eventId} is unique per event and
 * target, so a repeated dispatch is recognized. {@code sourceUpdatedAt} is ISO-8601 or null.
 */
public record InventorySignal(String eventId, String eventType, String sourceUpdatedAt) {
}
