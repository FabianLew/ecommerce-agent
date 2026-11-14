package com.ecommerceagent.identity;

import java.util.Set;
import java.util.UUID;

/** A store the user is a member of, with the user's roles in it. */
public record AccessibleStore(
        UUID storeId,
        UUID organizationId,
        String organizationName,
        String name,
        String currency,
        String timezone,
        Set<StoreRole> roles) {
}
