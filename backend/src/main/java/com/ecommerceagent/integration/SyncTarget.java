package com.ecommerceagent.integration;

import java.util.UUID;

/** A connection that needs incremental sync, with the store it belongs to (worker side). */
public record SyncTarget(UUID connectionId, UUID organizationId, UUID storeId) {
}
