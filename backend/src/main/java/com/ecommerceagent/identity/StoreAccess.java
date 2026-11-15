package com.ecommerceagent.identity;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ecommerceagent.identity.Actor.SystemActor;
import com.ecommerceagent.identity.Actor.UserActor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The trusted factory for {@link TrustedStoreScope} (ADR-0004). Every user query is filtered by the
 * user's memberships; a missing membership and a missing store are indistinguishable to callers.
 */
@Service
public class StoreAccess {

    private static final String ACCESSIBLE_STORES = """
            SELECT s.id, s.organization_id, o.name AS organization_name, s.name, s.currency, s.timezone,
                   array_agg(m.role ORDER BY m.role) AS roles
            FROM store_membership m
            JOIN store s ON s.id = m.store_id AND s.organization_id = m.organization_id
            JOIN organization o ON o.id = s.organization_id
            WHERE m.user_id = :userId
            """;

    private static final Set<StorePermission> SYSTEM_PERMISSIONS = Set.of(StorePermission.STORE_READ);

    private final JdbcClient jdbc;

    StoreAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AccessibleStore> accessibleStores(UUID userId) {
        return jdbc.sql(ACCESSIBLE_STORES + " GROUP BY s.id, o.name ORDER BY o.name, s.name")
                .param("userId", userId)
                .query(StoreAccess::mapStore)
                .list();
    }

    /** Builds a user scope only if the user currently has a membership in the store. */
    public Optional<TrustedStoreScope> scopeFor(UUID userId, UUID storeId) {
        return find(userId, storeId).map(store -> userScope(userId, store));
    }

    /**
     * Builds a scope for a backend service acting without a signed-in user (e.g. scheduled monitoring).
     * The store must exist in the given organization. System scopes can only read; changes go through
     * the policy and executor path, never through a broader system scope.
     */
    public Optional<TrustedStoreScope> systemScope(String service, UUID organizationId, UUID storeId) {
        SystemActor actor = new SystemActor(service);
        return storeExists(organizationId, storeId)
                ? Optional.of(new TrustedStoreScope(organizationId, storeId, actor, Set.of(), SYSTEM_PERMISSIONS))
                : Optional.empty();
    }

    /**
     * Re-checks the actor against the database right before a sensitive operation and returns a fresh
     * scope. A scope or approval created earlier never outlives a revoked membership or a changed role.
     *
     * @throws StoreAccessDeniedException if the actor no longer holds the permission in this store
     */
    public TrustedStoreScope reauthorize(TrustedStoreScope scope, StorePermission permission) {
        Optional<TrustedStoreScope> current = switch (scope.actor()) {
            case UserActor user -> scopeFor(user.userId(), scope.storeId());
            case SystemActor system -> systemScope(system.service(), scope.organizationId(), scope.storeId());
        };
        return current
                .filter(fresh -> fresh.organizationId().equals(scope.organizationId()))
                .filter(fresh -> fresh.has(permission))
                .orElseThrow(() -> new StoreAccessDeniedException(scope.storeId(), permission));
    }

    /** Store details visible to a signed-in user in an already verified scope. */
    public AccessibleStore store(TrustedStoreScope scope) {
        UUID userId = scope.userId()
                .orElseThrow(() -> new IllegalArgumentException("Store details require a user scope"));
        return find(userId, scope.storeId())
                .filter(store -> store.organizationId().equals(scope.organizationId()))
                .orElseThrow(() -> new StoreAccessDeniedException(scope.storeId(), StorePermission.STORE_READ));
    }

    private static TrustedStoreScope userScope(UUID userId, AccessibleStore store) {
        return new TrustedStoreScope(store.organizationId(), store.storeId(), new UserActor(userId), store.roles(),
                StoreRole.permissionsOf(store.roles()));
    }

    /**
     * The people who currently hold the permission in the scope's store, with their e-mail address, for backend
     * notifications. Never derived from a payload: the store comes from the trusted scope.
     */
    public List<RegisteredUser> membersWith(TrustedStoreScope scope, StorePermission permission) {
        List<String> roles = Arrays.stream(StoreRole.values())
                .filter(role -> StoreRole.permissionsOf(Set.of(role)).contains(permission))
                .map(Enum::name)
                .toList();
        return jdbc.sql("""
                        SELECT DISTINCT u.id, u.email, u.display_name
                        FROM store_membership m
                        JOIN app_user u ON u.id = m.user_id
                        WHERE m.organization_id = :organizationId AND m.store_id = :storeId AND m.role IN (:roles)
                        ORDER BY u.id
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("roles", roles)
                .query((rs, n) -> new RegisteredUser(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name")))
                .list();
    }

    private boolean storeExists(UUID organizationId, UUID storeId) {
        return jdbc.sql("SELECT count(*) FROM store WHERE id = :storeId AND organization_id = :organizationId")
                .param("storeId", storeId)
                .param("organizationId", organizationId)
                .query(Long.class)
                .single() > 0;
    }

    private Optional<AccessibleStore> find(UUID userId, UUID storeId) {
        return jdbc.sql(ACCESSIBLE_STORES + " AND s.id = :storeId GROUP BY s.id, o.name")
                .param("userId", userId)
                .param("storeId", storeId)
                .query(StoreAccess::mapStore)
                .optional();
    }

    private static AccessibleStore mapStore(ResultSet rs, int rowNum) throws SQLException {
        return new AccessibleStore(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getString("organization_name"),
                rs.getString("name"),
                rs.getString("currency"),
                rs.getString("timezone"),
                roles(rs.getArray("roles")));
    }

    private static Set<StoreRole> roles(Array array) throws SQLException {
        Set<StoreRole> roles = Arrays.stream((Object[]) array.getArray())
                .map(Object::toString)
                .map(StoreRole::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(StoreRole.class)));
        return Set.copyOf(roles);
    }
}
