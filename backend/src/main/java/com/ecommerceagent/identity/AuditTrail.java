package com.ecommerceagent.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Writes to and reads the append-only {@code audit_event} table (ADR-0004). */
@Service
public class AuditTrail {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final Clock clock;

    AuditTrail(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(AuditEntry entry) {
        jdbc.sql("""
                        INSERT INTO audit_event (id, occurred_at, organization_id, store_id, actor_user_id, actor_service,
                                                 action, resource_type, resource_id, details)
                        VALUES (:id, :occurredAt, :organizationId, :storeId, :actorUserId, :actorService,
                                :action, :resourceType, :resourceId, CAST(:details AS jsonb))
                        """)
                .param("id", UUID.randomUUID())
                .param("occurredAt", Timestamp.from(clock.instant()))
                .param("organizationId", entry.organizationId())
                .param("storeId", entry.storeId())
                .param("actorUserId", entry.actorUserId())
                .param("actorService", entry.actorService())
                .param("action", entry.action())
                .param("resourceType", entry.resourceType())
                .param("resourceId", entry.resourceId())
                .param("details", JSON.writeValueAsString(entry.details()))
                .update();
    }

    /**
     * Events of the store with one of {@code actions}, newest first, strictly older than the keyset position
     * ({@code beforeAt}, {@code beforeId}) when it is given.
     */
    public List<AuditRecord> ofStore(TrustedStoreScope scope, Collection<String> actions, Instant beforeAt,
            UUID beforeId, int limit) {
        if (actions.isEmpty()) {
            return List.of();
        }
        String keyset = beforeAt == null ? "" : " AND (e.occurred_at, e.id) < (:beforeAt, :beforeId)";
        var statement = jdbc.sql("""
                        SELECT e.id, e.occurred_at, e.action, e.actor_user_id, u.display_name, e.actor_service,
                               e.resource_type, e.resource_id, e.details
                        FROM audit_event e
                        LEFT JOIN app_user u ON u.id = e.actor_user_id
                        WHERE e.organization_id = :organizationId AND e.store_id = :storeId
                          AND e.action IN (:actions)""" + keyset + """

                        ORDER BY e.occurred_at DESC, e.id DESC
                        LIMIT :limit
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("actions", List.copyOf(actions))
                .param("limit", limit);
        if (beforeAt != null) {
            statement = statement.param("beforeAt", Timestamp.from(beforeAt)).param("beforeId", beforeId);
        }
        return statement.query(AuditTrail::map).list();
    }

    /** One event of the store with one of {@code actions}; empty for another store's event or another action. */
    public java.util.Optional<AuditRecord> find(TrustedStoreScope scope, Collection<String> actions, UUID id) {
        if (actions.isEmpty()) {
            return java.util.Optional.empty();
        }
        return jdbc.sql("""
                        SELECT e.id, e.occurred_at, e.action, e.actor_user_id, u.display_name, e.actor_service,
                               e.resource_type, e.resource_id, e.details
                        FROM audit_event e
                        LEFT JOIN app_user u ON u.id = e.actor_user_id
                        WHERE e.organization_id = :organizationId AND e.store_id = :storeId
                          AND e.action IN (:actions) AND e.id = :id
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("actions", List.copyOf(actions))
                .param("id", id)
                .query(AuditTrail::map)
                .optional();
    }

    private static AuditRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Map<String, String> details = new TreeMap<>();
        JSON.readTree(rs.getString("details")).properties()
                .forEach(entry -> details.put(entry.getKey(), entry.getValue().asString()));
        return new AuditRecord(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("action"), rs.getObject("actor_user_id", UUID.class), rs.getString("display_name"),
                rs.getString("actor_service"), rs.getString("resource_type"), rs.getString("resource_id"), details);
    }
}
