package com.ecommerceagent.identity;

import java.util.UUID;

/**
 * The actor lacks a permission in the store, or lost it since the scope was built. Callers must not
 * proceed with the operation; the web layer maps this to 403 (ADR-0004).
 */
public class StoreAccessDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID storeId;
    private final StorePermission permission;

    public StoreAccessDeniedException(UUID storeId, StorePermission permission) {
        super("Permission " + permission + " not granted in store " + storeId);
        this.storeId = storeId;
        this.permission = permission;
    }

    public UUID storeId() {
        return storeId;
    }

    public StorePermission permission() {
        return permission;
    }
}
