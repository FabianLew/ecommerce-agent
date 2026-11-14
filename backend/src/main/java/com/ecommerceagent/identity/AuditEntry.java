package com.ecommerceagent.identity;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.ecommerceagent.identity.Actor.SystemActor;
import com.ecommerceagent.identity.Actor.UserActor;

/**
 * One append-only audit record. Tenant fields are null only when no scope could be resolved. The actor
 * is either a user id or a system service name, never both. Details must not carry personal data
 * beyond identifiers (ADR-0004, audit retention and PII minimization).
 */
public record AuditEntry(
        String action,
        UUID organizationId,
        UUID storeId,
        UUID actorUserId,
        String actorService,
        String resourceType,
        String resourceId,
        Map<String, String> details) {

    public AuditEntry {
        Objects.requireNonNull(action, "action");
        if (actorUserId != null && actorService != null) {
            throw new IllegalArgumentException("An audit entry has either a user or a system actor");
        }
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /** Entry for a user actor (or no actor) outside a resolved scope. */
    public AuditEntry(String action, UUID organizationId, UUID storeId, UUID actorUserId, String resourceType,
            String resourceId, Map<String, String> details) {
        this(action, organizationId, storeId, actorUserId, null, resourceType, resourceId, details);
    }

    public static AuditEntry inScope(TrustedStoreScope scope, String action, String resourceType, String resourceId,
            Map<String, String> details) {
        return switch (scope.actor()) {
            case UserActor user -> new AuditEntry(action, scope.organizationId(), scope.storeId(), user.userId(), null,
                    resourceType, resourceId, details);
            case SystemActor system -> new AuditEntry(action, scope.organizationId(), scope.storeId(), null,
                    system.service(), resourceType, resourceId, details);
        };
    }

    public static AuditEntry inScope(TrustedStoreScope scope, String action, String resourceType, String resourceId) {
        return inScope(scope, action, resourceType, resourceId, Map.of());
    }
}
