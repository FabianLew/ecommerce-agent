package com.ecommerceagent.web;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.ecommerceagent.identity.AccessibleStore;
import com.ecommerceagent.identity.RegisteredUser;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StoreRole;
import com.ecommerceagent.identity.TrustedStoreScope;
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
        return StoreResponse.from(storeAccess.store(scope));
    }

    record MeResponse(UUID userId, String email, String displayName) {
    }

    record StoreResponse(UUID storeId, UUID organizationId, String organizationName, String name,
            String currency, String timezone, Set<StoreRole> roles) {

        static StoreResponse from(AccessibleStore store) {
            return new StoreResponse(store.storeId(), store.organizationId(), store.organizationName(),
                    store.name(), store.currency(), store.timezone(), store.roles());
        }
    }

    /** List envelope from document 04 §3; stores are not paginated yet. */
    record ListResponse<T>(List<T> items, String nextCursor, boolean hasMore) {

        static <T> ListResponse<T> complete(List<T> items) {
            return new ListResponse<>(items, null, false);
        }
    }
}
