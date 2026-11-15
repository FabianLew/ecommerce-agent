package com.ecommerceagent.integration;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Watermarks and poll/reconciliation runs of incremental sync (ADR-0010). */
@Repository
class SyncStateRepository {

    private final JdbcClient jdbc;
    private final SyncRunRepository runs;
    private final Clock clock;

    SyncStateRepository(JdbcClient jdbc, SyncRunRepository runs, Clock clock) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.clock = clock;
    }

    Optional<Instant> watermark(TrustedStoreScope scope, UUID connectionId, SyncStream stream) {
        return jdbc.sql("""
                        SELECT watermark FROM sync_watermark
                        WHERE connection_id = :connectionId AND stream = :stream
                          AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("connectionId", connectionId)
                .param("stream", stream.name())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    /** Moves the watermark forward only; an older value never replaces a newer one. */
    void advance(TrustedStoreScope scope, UUID connectionId, SyncStream stream, Instant watermark) {
        jdbc.sql("""
                        INSERT INTO sync_watermark (connection_id, organization_id, store_id, stream, watermark,
                                                    updated_at)
                        SELECT c.id, c.organization_id, c.store_id, :stream, :watermark, :now
                        FROM integration_connection c
                        WHERE c.id = :connectionId AND c.organization_id = :organizationId AND c.store_id = :storeId
                        ON CONFLICT (connection_id, stream) DO UPDATE
                            SET watermark = greatest(sync_watermark.watermark, EXCLUDED.watermark),
                                updated_at = EXCLUDED.updated_at
                        """)
                .param("stream", stream.name())
                .param("watermark", Timestamp.from(watermark))
                .param("now", Timestamp.from(clock.instant()))
                .param("connectionId", connectionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    /** Moves the watermark back only, for the history import (ADR-0015); a later value never replaces it. */
    void moveBack(TrustedStoreScope scope, UUID connectionId, SyncStream stream, Instant watermark) {
        jdbc.sql("""
                        INSERT INTO sync_watermark (connection_id, organization_id, store_id, stream, watermark,
                                                    updated_at)
                        SELECT c.id, c.organization_id, c.store_id, :stream, :watermark, :now
                        FROM integration_connection c
                        WHERE c.id = :connectionId AND c.organization_id = :organizationId AND c.store_id = :storeId
                        ON CONFLICT (connection_id, stream) DO UPDATE
                            SET watermark = least(sync_watermark.watermark, EXCLUDED.watermark),
                                updated_at = EXCLUDED.updated_at
                        """)
                .param("stream", stream.name())
                .param("watermark", Timestamp.from(watermark))
                .param("now", Timestamp.from(clock.instant()))
                .param("connectionId", connectionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    void recordCheck(TrustedStoreScope scope, UUID connectionId, SyncCheck check) {
        jdbc.sql("""
                        INSERT INTO sync_check (id, organization_id, store_id, connection_id, kind, status, started_at,
                                                finished_at, changes, missed_webhooks, error_code)
                        SELECT :id, c.organization_id, c.store_id, c.id, :kind, :status, :startedAt, :finishedAt,
                               :changes, :missed, :error
                        FROM integration_connection c
                        WHERE c.id = :connectionId AND c.organization_id = :organizationId AND c.store_id = :storeId
                        """)
                .param("id", UUID.randomUUID())
                .param("kind", check.kind().name())
                .param("status", check.status().name())
                .param("startedAt", Timestamp.from(check.startedAt()))
                .param("finishedAt", Timestamp.from(check.finishedAt()))
                .param("changes", check.changes())
                .param("missed", check.missedWebhooks())
                .param("error", check.errorCode())
                .param("connectionId", connectionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    /** The latest run of a kind, and the latest successful one (they differ when the last run failed). */
    Optional<SyncCheck> latest(TrustedStoreScope scope, UUID connectionId, SyncCheck.Kind kind, boolean succeededOnly) {
        return jdbc.sql("""
                        SELECT kind, status, started_at, finished_at, changes, missed_webhooks, error_code
                        FROM sync_check
                        WHERE connection_id = :connectionId AND kind = :kind
                          AND (NOT :succeededOnly OR status = 'SUCCEEDED')
                          AND organization_id = :organizationId AND store_id = :storeId
                        ORDER BY started_at DESC
                        LIMIT 1
                        """)
                .param("connectionId", connectionId)
                .param("kind", kind.name())
                .param("succeededOnly", succeededOnly)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query((rs, n) -> new SyncCheck(SyncCheck.Kind.valueOf(rs.getString("kind")),
                        SyncCheck.Status.valueOf(rs.getString("status")), rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at").toInstant(), rs.getInt("changes"),
                        rs.getInt("missed_webhooks"), rs.getString("error_code")))
                .optional();
    }

    int missedWebhooksSince(TrustedStoreScope scope, UUID connectionId, Duration window) {
        return jdbc.sql("""
                        SELECT coalesce(sum(missed_webhooks), 0) FROM sync_check
                        WHERE connection_id = :connectionId AND started_at >= :since
                          AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("connectionId", connectionId)
                .param("since", Timestamp.from(clock.instant().minus(window)))
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(Integer.class)
                .single();
    }

    int failedInboxItemsSince(TrustedStoreScope scope, UUID connectionId, Duration window) {
        return jdbc.sql("""
                        SELECT count(*) FROM webhook_inbox
                        WHERE connection_id = :connectionId AND status = 'FAILED' AND received_at >= :since
                          AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("connectionId", connectionId)
                .param("since", Timestamp.from(clock.instant().minus(window)))
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(Integer.class)
                .single();
    }

    /**
     * Connections that need polling schedules: connected, with a successful initial import (worker side,
     * across stores). The store ids are used only to build a verified system scope.
     */
    List<SyncTarget> syncTargets(TrustedStoreScope scope) {
        return jdbc.sql("""
                        SELECT c.id, c.organization_id, c.store_id FROM integration_connection c
                        WHERE c.organization_id = :organizationId AND c.store_id = :storeId
                          AND c.status = 'CONNECTED'
                          AND EXISTS (SELECT 1 FROM sync_run r WHERE r.connection_id = c.id
                                      AND r.kind = 'INITIAL_IMPORT' AND r.status = 'SUCCEEDED')
                        ORDER BY c.id
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query((rs, n) -> new SyncTarget(rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("store_id", UUID.class)))
                .list();
    }

    /** The latest successful initial import, which seeds the watermarks. */
    Optional<SyncRun> lastSuccessfulImport(TrustedStoreScope scope, UUID connectionId) {
        return jdbc.sql("""
                        SELECT id FROM sync_run
                        WHERE connection_id = :connectionId AND kind = 'INITIAL_IMPORT' AND status = 'SUCCEEDED'
                          AND organization_id = :organizationId AND store_id = :storeId
                        ORDER BY started_at DESC LIMIT 1
                        """)
                .param("connectionId", connectionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(UUID.class)
                .optional()
                .flatMap(id -> runs.find(scope, id));
    }
}
