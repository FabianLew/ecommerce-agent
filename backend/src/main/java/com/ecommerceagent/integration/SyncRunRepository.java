package com.ecommerceagent.integration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SyncRunRepository {

    private static final String COLUMNS = """
            id, connection_id, status, window_from, window_until, products_imported, variants_imported,
            orders_imported, orders_expected, error_code, started_at, finished_at
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    SyncRunRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    SyncRun create(TrustedStoreScope scope, UUID connectionId, UUID requestedBy, Instant from, Instant until) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                            INSERT INTO sync_run (id, organization_id, store_id, connection_id, kind, status,
                                                  requested_by_user_id, window_from, window_until, started_at)
                            VALUES (:id, :organizationId, :storeId, :connectionId, 'INITIAL_IMPORT', 'RUNNING',
                                    :requestedBy, :from, :until, :now)
                            """)
                    .param("id", id)
                    .param("organizationId", scope.organizationId())
                    .param("storeId", scope.storeId())
                    .param("connectionId", connectionId)
                    .param("requestedBy", requestedBy)
                    .param("from", Timestamp.from(from))
                    .param("until", Timestamp.from(until))
                    .param("now", Timestamp.from(clock.instant()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new SyncInProgressException();
        }
        return find(scope, id).orElseThrow();
    }

    Optional<SyncRun> find(TrustedStoreScope scope, UUID runId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM sync_run WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("id", runId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(SyncRunRepository::map)
                .optional();
    }

    Optional<SyncRun> latest(TrustedStoreScope scope, UUID connectionId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM sync_run
                        WHERE connection_id = :connectionId AND organization_id = :organizationId AND store_id = :storeId
                        ORDER BY started_at DESC LIMIT 1
                        """)
                .param("connectionId", connectionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(SyncRunRepository::map)
                .optional();
    }

    void recordCatalog(TrustedStoreScope scope, UUID runId, int products, int variants) {
        update(scope, runId, "products_imported = :a, variants_imported = :b", products, variants);
    }

    void recordOrders(TrustedStoreScope scope, UUID runId, int imported, int expected) {
        update(scope, runId, "orders_imported = :a, orders_expected = :b", imported, expected);
    }

    /** Ends a running run; a finished run is never changed again. */
    boolean finish(TrustedStoreScope scope, UUID runId, SyncRunStatus status, String errorCode) {
        return jdbc.sql("""
                        UPDATE sync_run SET status = :status, error_code = :errorCode, finished_at = :now
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                          AND status = 'RUNNING'
                        """)
                .param("status", status.name())
                .param("errorCode", errorCode)
                .param("now", Timestamp.from(clock.instant()))
                .param("id", runId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update() == 1;
    }

    private void update(TrustedStoreScope scope, UUID runId, String set, int a, int b) {
        jdbc.sql("UPDATE sync_run SET " + set + """
                         WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("a", a)
                .param("b", b)
                .param("id", runId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    private static SyncRun map(ResultSet rs, int rowNum) throws SQLException {
        return new SyncRun(
                rs.getObject("id", UUID.class),
                rs.getObject("connection_id", UUID.class),
                SyncRunStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("window_from").toInstant(),
                rs.getTimestamp("window_until").toInstant(),
                (Integer) rs.getObject("products_imported"),
                (Integer) rs.getObject("variants_imported"),
                (Integer) rs.getObject("orders_imported"),
                (Integer) rs.getObject("orders_expected"),
                rs.getString("error_code"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
    }
}
