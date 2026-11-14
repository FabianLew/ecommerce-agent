package com.ecommerceagent.identity;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One append-only audit record. Tenant fields are null only when no scope could be resolved. */
public record AuditEntry(
        String action,
        UUID organizationId,
        UUID storeId,
        UUID actorUserId,
        String resourceType,
        String resourceId,
        Map<String, String> details) {

    public AuditEntry {
        Objects.requireNonNull(action, "action");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static AuditEntry inScope(TrustedStoreScope scope, String action, String resourceType, String resourceId) {
        return new AuditEntry(action, scope.organizationId(), scope.storeId(), scope.userId(),
                resourceType, resourceId, Map.of());
    }
}
