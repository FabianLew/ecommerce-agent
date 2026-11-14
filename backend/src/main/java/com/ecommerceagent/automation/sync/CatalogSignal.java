package com.ecommerceagent.automation.sync;

/** A request to re-read one product of the store from the source (ADR-0010). */
public record CatalogSignal(String eventId, String productExternalId) {
}
