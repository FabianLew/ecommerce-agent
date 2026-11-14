package com.ecommerceagent.identity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.ecommerceagent.identity.Actor.UserActor;

/**
 * The only store scope passed between modules (ADR-0004). It cannot be constructed outside this
 * module: {@link StoreAccess} builds it after verifying the actor and the store in the database, never
 * from model output or request payloads. Holding a scope proves only that the check passed when it was
 * built; sensitive actions call {@link StoreAccess#reauthorize} right before they execute.
 */
public final class TrustedStoreScope {

    private final UUID organizationId;
    private final UUID storeId;
    private final Actor actor;
    private final Set<StoreRole> roles;
    private final Set<StorePermission> permissions;

    TrustedStoreScope(UUID organizationId, UUID storeId, Actor actor, Set<StoreRole> roles,
            Set<StorePermission> permissions) {
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.roles = ordered(roles, StoreRole.class);
        this.permissions = ordered(permissions, StorePermission.class);
        if (actor instanceof UserActor && this.roles.isEmpty()) {
            throw new IllegalArgumentException("A user scope requires at least one role");
        }
        if (this.permissions.isEmpty()) {
            throw new IllegalArgumentException("A store scope requires at least one permission");
        }
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID storeId() {
        return storeId;
    }

    public Actor actor() {
        return actor;
    }

    /** The signed-in user, empty for system actors. */
    public Optional<UUID> userId() {
        return actor instanceof UserActor user ? Optional.of(user.userId()) : Optional.empty();
    }

    /** Store roles of a user actor; empty for system actors. */
    public Set<StoreRole> roles() {
        return roles;
    }

    public Set<StorePermission> permissions() {
        return permissions;
    }

    public boolean has(StorePermission permission) {
        return permissions.contains(permission);
    }

    /** Fails if the permission was not granted when this scope was built. */
    public void require(StorePermission permission) {
        if (!has(permission)) {
            throw new StoreAccessDeniedException(storeId, permission);
        }
    }

    private static <E extends Enum<E>> Set<E> ordered(Set<E> values, Class<E> type) {
        EnumSet<E> copy = EnumSet.noneOf(type);
        copy.addAll(values);
        return Collections.unmodifiableSet(copy);
    }

    @Override
    public String toString() {
        return "TrustedStoreScope[organizationId=" + organizationId + ", storeId=" + storeId + ", actor=" + actor
                + ", permissions=" + permissions + "]";
    }
}
