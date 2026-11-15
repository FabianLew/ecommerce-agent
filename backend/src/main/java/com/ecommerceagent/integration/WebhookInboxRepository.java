package com.ecommerceagent.integration;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Accepted webhook deliveries (ADR-0009). Payloads are already minimized when they reach this class. */
@Repository
class WebhookInboxRepository {

    private final JdbcClient jdbc;
    private final Clock clock;

    WebhookInboxRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Stores a delivery; returns false if the same body for the same topic was stored before. */
    boolean insert(TrustedStoreScope scope, UUID connectionId, Delivery delivery, String payloadSha256,
            String payloadJson, Instant receivedAt) {
        return jdbc.sql("""
                        INSERT INTO webhook_inbox (id, organization_id, store_id, connection_id, topic,
                                                   source_webhook_id, source_delivery_id, payload_sha256, payload,
                                                   status, next_attempt_at, received_at)
                        SELECT :id, c.organization_id, c.store_id, c.id, :topic, :webhookId, :deliveryId, :sha,
                               CAST(:payload AS jsonb), 'RECEIVED', :receivedAt, :receivedAt
                        FROM integration_connection c
                        WHERE c.id = :connectionId AND c.organization_id = :organizationId AND c.store_id = :storeId
                        ON CONFLICT (connection_id, topic, payload_sha256) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("topic", delivery.topic())
                .param("webhookId", delivery.webhookId())
                .param("deliveryId", delivery.deliveryId())
                .param("sha", payloadSha256)
                .param("payload", payloadJson)
                .param("receivedAt", Timestamp.from(receivedAt))
                .param("connectionId", connectionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update() == 1;
    }

    /**
     * Locks the next due item of any store for the caller's transaction (worker side). The item carries its
     * store ids only so that the caller can build a verified system scope from them.
     */
    Optional<InboxItem> claimNext(TrustedStoreScope scope) {
        return jdbc.sql("""
                        SELECT id, organization_id, store_id, connection_id, topic, payload::text AS payload, attempts
                        FROM webhook_inbox
                        WHERE organization_id = :organizationId AND store_id = :storeId
                          AND status = 'RECEIVED' AND next_attempt_at <= :now
                        ORDER BY next_attempt_at, seq
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("now", Timestamp.from(clock.instant()))
                .query((rs, n) -> new InboxItem(rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("store_id", UUID.class),
                        rs.getObject("connection_id", UUID.class), rs.getString("topic"), rs.getString("payload"),
                        rs.getInt("attempts")))
                .optional();
    }

    void finish(TrustedStoreScope scope, UUID id, InboxStatus status, String outcome) {
        jdbc.sql("""
                        UPDATE webhook_inbox
                        SET status = :status, outcome = :outcome, processed_at = :now, attempts = attempts + 1
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("status", status.name())
                .param("outcome", outcome)
                .param("now", Timestamp.from(clock.instant()))
                .param("id", id)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    /** Counts a failed attempt; the item is retried at {@code nextAttemptAt} or fails after the last attempt. */
    void recordFailure(TrustedStoreScope scope, UUID id, int maxAttempts, Instant nextAttemptAt, String outcome) {
        jdbc.sql("""
                        UPDATE webhook_inbox
                        SET attempts = attempts + 1, outcome = :outcome, next_attempt_at = :next,
                            status = CASE WHEN attempts + 1 >= :max THEN 'FAILED' ELSE status END,
                            processed_at = CASE WHEN attempts + 1 >= :max THEN :now ELSE processed_at END
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                          AND status = 'RECEIVED'
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("outcome", outcome)
                .param("next", Timestamp.from(nextAttemptAt))
                .param("max", maxAttempts)
                .param("now", Timestamp.from(clock.instant()))
                .param("id", id)
                .update();
    }

    List<InboxEntry> list(TrustedStoreScope scope, UUID connectionId) {
        return jdbc.sql("""
                        SELECT id, topic, payload::text AS payload, status, attempts, outcome, received_at
                        FROM webhook_inbox
                        WHERE connection_id = :connectionId AND organization_id = :organizationId
                          AND store_id = :storeId
                        ORDER BY seq
                        """)
                .param("connectionId", connectionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query((rs, n) -> new InboxEntry(rs.getObject("id", UUID.class), rs.getString("topic"),
                        rs.getString("payload"), InboxStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
                        rs.getString("outcome"), rs.getTimestamp("received_at").toInstant()))
                .list();
    }

    /** Headers of a delivery that we keep. */
    record Delivery(String topic, String webhookId, String deliveryId) {
    }

    record InboxItem(UUID id, UUID organizationId, UUID storeId, UUID connectionId, String topic, String payload,
            int attempts) {
    }

    record InboxEntry(UUID id, String topic, String payload, InboxStatus status, int attempts, String outcome,
            Instant receivedAt) {
    }

    enum InboxStatus {
        RECEIVED,
        PROCESSED,
        IGNORED,
        FAILED
    }
}
