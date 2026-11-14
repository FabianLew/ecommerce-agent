package com.ecommerceagent.automation.temporal;

import java.util.UUID;

import com.ecommerceagent.identity.Actor.SystemActor;
import com.ecommerceagent.identity.Actor.UserActor;
import com.ecommerceagent.identity.TrustedStoreScope;

/**
 * Context carried in Temporal headers from the starter to workflows and activities. The actor is a user
 * ({@code userId}) or a backend service ({@code service}), never both. All IDs are a claim only:
 * activities rebuild the scope in {@code StoreAccess} before use (ADR-0004).
 */
public record WorkflowStoreContext(UUID organizationId, UUID storeId, UUID userId, String service,
        String correlationId) {

    /** Context for a workflow started within an already verified scope. */
    public static WorkflowStoreContext of(TrustedStoreScope scope, String correlationId) {
        return switch (scope.actor()) {
            case UserActor user -> new WorkflowStoreContext(scope.organizationId(), scope.storeId(), user.userId(),
                    null, correlationId);
            case SystemActor system -> new WorkflowStoreContext(scope.organizationId(), scope.storeId(), null,
                    system.service(), correlationId);
        };
    }

    static WorkflowStoreContext correlationOnly(String correlationId) {
        return new WorkflowStoreContext(null, null, null, null, correlationId);
    }

    boolean hasStoreClaim() {
        return organizationId != null && storeId != null && (userId == null) != (service == null);
    }

    boolean isEmpty() {
        return organizationId == null && storeId == null && userId == null && service == null
                && correlationId == null;
    }
}
