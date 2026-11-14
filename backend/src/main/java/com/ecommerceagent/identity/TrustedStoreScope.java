package com.ecommerceagent.identity;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The only store scope passed between modules (ADR-0004). Built exclusively by the backend from a
 * verified membership, never from model output or request payloads. Use {@link StoreAccess#scopeFor}.
 */
public record TrustedStoreScope(UUID organizationId, UUID storeId, UUID userId, Set<StoreRole> roles) {

    public TrustedStoreScope {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(userId, "userId");
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("A store scope requires at least one role");
        }
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(StoreRole role) {
        return roles.contains(role);
    }
}
