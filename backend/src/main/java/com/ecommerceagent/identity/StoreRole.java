package com.ecommerceagent.identity;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Working store-scoped roles from document 04 §11 [P]. Until the full matrix is decided (O-13), every
 * role can read the store and only {@link #OWNER} can manage it (ADR-0004).
 */
public enum StoreRole {
    OWNER(StorePermission.STORE_READ, StorePermission.STORE_MANAGE),
    DECISION_OPERATOR(StorePermission.STORE_READ),
    SUPPORT_OPERATOR(StorePermission.STORE_READ),
    ANALYST(StorePermission.STORE_READ);

    private final Set<StorePermission> permissions;

    StoreRole(StorePermission first, StorePermission... rest) {
        this.permissions = Set.copyOf(EnumSet.of(first, rest));
    }

    public Set<StorePermission> permissions() {
        return permissions;
    }

    /** Union of the roles' permissions, in declaration order. */
    public static Set<StorePermission> permissionsOf(Collection<StoreRole> roles) {
        EnumSet<StorePermission> result = EnumSet.noneOf(StorePermission.class);
        roles.forEach(role -> result.addAll(role.permissions));
        return Collections.unmodifiableSet(result);
    }
}
