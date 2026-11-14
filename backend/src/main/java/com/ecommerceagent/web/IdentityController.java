package com.ecommerceagent.web;

import java.util.Set;
import java.util.UUID;

import com.ecommerceagent.identity.AccessibleStore;
import com.ecommerceagent.identity.RegisteredUser;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.StoreRole;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Current user and their stores; the frontend's StoreSwitcher (B-09) reads these. */
@RestController
@RequestMapping("/api/v1")
class IdentityController {

    private final StoreAccess storeAccess;

    IdentityController(StoreAccess storeAccess) {
        this.storeAccess = storeAccess;
    }

    @GetMapping("/me")
    MeResponse me(RegisteredUser user) {
        return new MeResponse(user.id(), user.email(), user.displayName());
    }

    @GetMapping("/stores")
    ListResponse<StoreResponse> stores(RegisteredUser user) {
        return ListResponse.complete(storeAccess.accessibleStores(user.id()).stream()
                .map(StoreResponse::from)
                .toList());
    }

    @GetMapping("/stores/{storeId}")
    StoreResponse store(TrustedStoreScope scope) {
        return StoreResponse.from(storeAccess.store(scope), scope.permissions());
    }

    record MeResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId, String email, String displayName) {
    }

    record StoreResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID storeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID organizationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String organizationName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String timezone,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<StoreRole> roles,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<StorePermission> permissions) {

        static StoreResponse from(AccessibleStore store) {
            return from(store, StoreRole.permissionsOf(store.roles()));
        }

        static StoreResponse from(AccessibleStore store, Set<StorePermission> permissions) {
            return new StoreResponse(store.storeId(), store.organizationId(), store.organizationName(),
                    store.name(), store.currency(), store.timezone(), store.roles(), permissions);
        }
    }
}
