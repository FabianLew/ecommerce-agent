package com.ecommerceagent.intelligence.model;

import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.stereotype.Component;

/** Production check: {@link StoreAccess#reauthorize} for reading the store, against the database. */
@Component
class StoreAccessCallAuthorization implements AiCallAuthorization {

    private final StoreAccess storeAccess;

    StoreAccessCallAuthorization(StoreAccess storeAccess) {
        this.storeAccess = storeAccess;
    }

    @Override
    public void check(TrustedStoreScope scope) {
        storeAccess.reauthorize(scope, StorePermission.STORE_READ);
    }
}
