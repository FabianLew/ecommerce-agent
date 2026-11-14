package com.ecommerceagent.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Writes to the append-only {@code audit_event} table (ADR-0004). */
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
                        INSERT INTO audit_event (id, occurred_at, organization_id, store_id, actor_user_id,
                                                 action, resource_type, resource_id, details)
                        VALUES (:id, :occurredAt, :organizationId, :storeId, :actorUserId,
                                :action, :resourceType, :resourceId, CAST(:details AS jsonb))
                        """)
                .param("id", UUID.randomUUID())
                .param("occurredAt", Timestamp.from(clock.instant()))
                .param("organizationId", entry.organizationId())
                .param("storeId", entry.storeId())
                .param("actorUserId", entry.actorUserId())
                .param("action", entry.action())
                .param("resourceType", entry.resourceType())
                .param("resourceId", entry.resourceId())
                .param("details", JSON.writeValueAsString(entry.details()))
                .update();
    }
}
