package com.ecommerceagent.identity;

import java.util.EnumSet;
import java.util.UUID;

/** A store scope for unit tests that run without a database. Production code gets scopes only from StoreAccess. */
public final class TestScopes {

    private TestScopes() {
    }

    public static TrustedStoreScope system(UUID organizationId, UUID storeId) {
        return new TrustedStoreScope(organizationId, storeId, new Actor.SystemActor("unit-test"),
                EnumSet.noneOf(StoreRole.class), EnumSet.allOf(StorePermission.class));
    }
}
