package com.ecommerceagent.automation.temporal;

import java.util.UUID;

/**
 * Context carried in Temporal headers from the starter to workflows and activities. The store IDs
 * are a claim only: activities re-verify them against memberships before use (ADR-0004).
 */
public record WorkflowStoreContext(UUID organizationId, UUID storeId, UUID userId, String correlationId) {

    boolean hasStoreClaim() {
        return organizationId != null && storeId != null && userId != null;
    }
}
