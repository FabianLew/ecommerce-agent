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

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Resolves which stores a user may access. Every query here is filtered by the user's memberships;
 * a missing membership and a missing store are indistinguishable to callers (ADR-0004).
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

    /** Builds the trusted scope only if the user is a member of the store. */
    public Optional<TrustedStoreScope> scopeFor(UUID userId, UUID storeId) {
        return find(userId, storeId)
                .map(store -> new TrustedStoreScope(store.organizationId(), store.storeId(), userId, store.roles()));
    }

    /** Store details for an already verified scope. */
    public AccessibleStore store(TrustedStoreScope scope) {
        return find(scope.userId(), scope.storeId())
                .filter(store -> store.organizationId().equals(scope.organizationId()))
                .orElseThrow(() -> new IllegalStateException("Scope no longer valid for store " + scope.storeId()));
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
