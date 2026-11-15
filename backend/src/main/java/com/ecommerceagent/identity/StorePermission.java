package com.ecommerceagent.identity;

/**
 * Minimal store permissions enforced from B-06 (ADR-0004). The full role matrix (O-13) refines the
 * mapping in {@link StoreRole}; code checks permissions, never role names.
 */
public enum StorePermission {
    /** Read store data, decisions and reports. */
    STORE_READ,
    /** Change store settings, integrations and memberships. */
    STORE_MANAGE,
    /** Approve or reject a proposed action, and stop mutations (ADR-0020). */
    DECISION_APPROVE
}
