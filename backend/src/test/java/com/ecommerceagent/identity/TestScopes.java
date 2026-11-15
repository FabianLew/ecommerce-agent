package com.ecommerceagent.identity;

import java.util.EnumSet;
import java.util.UUID;

/** A store scope for unit tests that run without a database. Production code gets scopes only from StoreAccess. */
public final class TestScopes {

    private TestScopes() {
    }

    public static TrustedStoreScope system(UUID organizationId, UUID storeId) {
        return system(organizationId, storeId, "unit-test");
    }

    public static TrustedStoreScope system(UUID organizationId, UUID storeId, String service) {
        return new TrustedStoreScope(organizationId, storeId, new Actor.SystemActor(service),
                EnumSet.noneOf(StoreRole.class), EnumSet.allOf(StorePermission.class));
    }
}
