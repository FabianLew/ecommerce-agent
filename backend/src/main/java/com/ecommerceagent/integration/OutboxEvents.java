package com.ecommerceagent.integration;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox (ADR-0009): events are written in the same transaction as the state they describe
 * and dispatched later (B-15). A dedup key makes re-processing the same input a no-op.
 */
@Repository
public class OutboxEvents {

    public static final String INVENTORY_CHANGED = "commerce.inventory.changed.v1";
    public static final String INVENTORY_VERIFICATION_REQUESTED = "commerce.inventory.verification-requested.v1";
    public static final String ORDER_CHANGED = "commerce.order.changed.v1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final Clock clock;

    OutboxEvents(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Appends an event in the caller's transaction; returns false if the dedup key was already used. */
    public boolean append(TrustedStoreScope scope, String eventType, String aggregateType, String aggregateId,
            Map<String, ?> payload, String dedupKey) {
        return jdbc.sql("""
                        INSERT INTO outbox_event (id, organization_id, store_id, event_type, aggregate_type,
                                                  aggregate_id, payload, dedup_key, created_at)
                        VALUES (:id, :organizationId, :storeId, :eventType, :aggregateType, :aggregateId,
                                CAST(:payload AS jsonb), :dedupKey, :now)
                        ON CONFLICT (store_id, dedup_key) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("eventType", eventType)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("payload", JSON.writeValueAsString(payload))
                .param("dedupKey", dedupKey)
                .param("now", Timestamp.from(clock.instant()))
                .update() == 1;
    }

    /** Events of this store not yet dispatched, oldest first. */
    public List<OutboxEvent> pending(TrustedStoreScope scope, int limit) {
        return jdbc.sql("""
                        SELECT id, event_type, aggregate_type, aggregate_id, payload::text AS payload, dedup_key,
                               created_at
                        FROM outbox_event
                        WHERE dispatched_at IS NULL AND organization_id = :organizationId AND store_id = :storeId
                        ORDER BY created_at, id
                        LIMIT :limit
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("limit", limit)
                .query((rs, n) -> new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getString("payload"),
                        rs.getString("dedup_key"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public record OutboxEvent(UUID id, String eventType, String aggregateType, String aggregateId, String payload,
            String dedupKey, Instant createdAt) {
    }
}
