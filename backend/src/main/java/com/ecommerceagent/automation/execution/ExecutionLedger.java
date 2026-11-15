package com.ecommerceagent.automation.execution;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.CanonicalJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The execution ledger, target leases and limit slots (ADR-0020 points 4 and 5). Every method filters by the trusted
 * scope. Transitions are checked against {@link ExecutionStatus}; a row never moves backwards.
 */
@Repository
@NamedInterface("execution")
public class ExecutionLedger {

    public enum Admission {
        ADMITTED,
        LIMIT_REACHED,
        COOLDOWN
    }

    private static final String COLUMNS = """
            id, idempotency_key, authorized_action_id, action_type, target_type, target_id, status, pre_state,
            request_hash, provider_request_id, result_code, write_sent, attempts, version, created_at, updated_at
            """;

    private final JdbcClient jdbc;
    private final ExecutionSettings settings;
    private final Clock clock;

    ExecutionLedger(JdbcClient jdbc, ExecutionSettings settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    public Optional<ActionExecution> find(TrustedStoreScope scope, UUID executionId) {
        return select(scope, "id = :value", executionId);
    }

    public Optional<ActionExecution> byKey(TrustedStoreScope scope, String idempotencyKey) {
        return select(scope, "idempotency_key = :value", idempotencyKey);
    }

    /** The ledger row of the action's key: created {@code PLANNED}, or the existing one, pointed at this action. */
    @Transactional
    public ActionExecution plan(TrustedStoreScope scope, AuthorizedAction action) {
        Timestamp now = Timestamp.from(clock.instant());
        int inserted = jdbc.sql("""
                        INSERT INTO action_execution (id, organization_id, store_id, idempotency_key,
                                                      authorized_action_id, action_type, target_type, target_id,
                                                      status, created_at, updated_at)
                        VALUES (:id, :organizationId, :storeId, :key, :actionId, :type, :targetType, :targetId,
                                'PLANNED', :now, :now)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("key", action.idempotencyKey())
                .param("actionId", action.id())
                .param("type", action.actionType())
                .param("targetType", action.targetType())
                .param("targetId", action.targetId())
                .param("now", now)
                .update();
        if (inserted == 0) {
            // A re-issued authorization of the same action continues the same row.
            jdbc.sql("""
                            UPDATE action_execution SET authorized_action_id = :actionId, updated_at = :now
                            WHERE idempotency_key = :key AND organization_id = :organizationId AND store_id = :storeId
                            """)
                    .param("actionId", action.id())
                    .param("now", now)
                    .param("key", action.idempotencyKey())
                    .param("organizationId", scope.organizationId())
                    .param("storeId", scope.storeId())
                    .update();
        }
        return byKey(scope, action.idempotencyKey()).orElseThrow();
    }

    /** Takes the target's lease for {@code owner}; an expired lease of another owner is taken over. */
    public boolean takeLease(TrustedStoreScope scope, String targetType, UUID targetId, String owner) {
        Instant now = clock.instant();
        return jdbc.sql("""
                        INSERT INTO action_target_lease (organization_id, store_id, target_type, target_id, owner,
                                                         lease_until)
                        VALUES (:organizationId, :storeId, :targetType, :targetId, :owner, :until)
                        ON CONFLICT (store_id, target_type, target_id) DO UPDATE
                            SET owner = EXCLUDED.owner, lease_until = EXCLUDED.lease_until
                            WHERE action_target_lease.owner = EXCLUDED.owner
                               OR action_target_lease.lease_until <= :now
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("owner", owner)
                .param("until", Timestamp.from(now.plus(settings.leaseDuration())))
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    /** Whether {@code owner} still holds the lease right now; checked again just before the write. */
    public boolean holdsLease(TrustedStoreScope scope, String targetType, UUID targetId, String owner) {
        return jdbc.sql("""
                        SELECT count(*) FROM action_target_lease
                        WHERE store_id = :storeId AND organization_id = :organizationId AND target_type = :targetType
                          AND target_id = :targetId AND owner = :owner AND lease_until > :now
                        """)
                .param("storeId", scope.storeId())
                .param("organizationId", scope.organizationId())
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("owner", owner)
                .param("now", Timestamp.from(clock.instant()))
                .query(Long.class).single() == 1;
    }

    public void releaseLease(TrustedStoreScope scope, String targetType, UUID targetId, String owner) {
        jdbc.sql("""
                        DELETE FROM action_target_lease
                        WHERE store_id = :storeId AND organization_id = :organizationId AND target_type = :targetType
                          AND target_id = :targetId AND owner = :owner
                        """)
                .param("storeId", scope.storeId())
                .param("organizationId", scope.organizationId())
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("owner", owner)
                .update();
    }

    /**
     * Admits the write: under a per-store lock, checks the cooldown of the target and the rolling limit, reserves a
     * slot unless this execution already holds one, and records the intent {@code IN_PROGRESS} with the pre-state.
     */
    @Transactional
    public Admission admit(TrustedStoreScope scope, UUID executionId, JsonNode preState, String requestHash) {
        ActionExecution execution = find(scope, executionId).orElseThrow();
        Instant now = clock.instant();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lock, 0))")
                .param("lock", "action-limit:" + scope.storeId() + ":" + execution.actionType())
                .query(rs -> {
                });
        long recent = jdbc.sql("""
                        SELECT count(*) FROM action_execution
                        WHERE store_id = :storeId AND organization_id = :organizationId AND target_type = :targetType
                          AND target_id = :targetId AND id <> :id AND status IN ('APPLIED', 'VERIFIED', 'UNKNOWN')
                          AND updated_at > :since
                        """)
                .param("storeId", scope.storeId())
                .param("organizationId", scope.organizationId())
                .param("targetType", execution.targetType())
                .param("targetId", execution.targetId())
                .param("id", executionId)
                .param("since", Timestamp.from(now.minus(settings.cooldownPerAd())))
                .query(Long.class).single();
        if (recent > 0) {
            return Admission.COOLDOWN;
        }
        boolean holdsSlot = jdbc.sql("SELECT count(*) FROM action_limit_slot WHERE execution_id = :id")
                .param("id", executionId).query(Long.class).single() > 0;
        if (!holdsSlot) {
            long used = jdbc.sql("""
                            SELECT count(*) FROM action_limit_slot
                            WHERE store_id = :storeId AND organization_id = :organizationId AND action_type = :type
                              AND released_at IS NULL AND reserved_at > :since
                            """)
                    .param("storeId", scope.storeId())
                    .param("organizationId", scope.organizationId())
                    .param("type", execution.actionType())
                    .param("since", Timestamp.from(now.minus(ExecutionSettings.LIMIT_WINDOW)))
                    .query(Long.class).single();
            if (used >= settings.maxPausesPerStore24h()) {
                return Admission.LIMIT_REACHED;
            }
            jdbc.sql("""
                            INSERT INTO action_limit_slot (execution_id, organization_id, store_id, action_type,
                                                           reserved_at)
                            VALUES (:id, :organizationId, :storeId, :type, :now)
                            """)
                    .param("id", executionId)
                    .param("organizationId", scope.organizationId())
                    .param("storeId", scope.storeId())
                    .param("type", execution.actionType())
                    .param("now", Timestamp.from(now))
                    .update();
        }
        int updated = jdbc.sql("""
                        UPDATE action_execution
                        SET status = 'IN_PROGRESS', pre_state = CAST(:preState AS jsonb), request_hash = :requestHash,
                            attempts = attempts + 1, version = version + 1, updated_at = :now
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                          AND status IN ('PLANNED', 'IN_PROGRESS', 'UNKNOWN')
                        """)
                .param("preState", CanonicalJson.write(preState))
                .param("requestHash", requestHash)
                .param("now", Timestamp.from(now))
                .param("id", executionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Execution " + executionId + " cannot start from " + execution.status());
        }
        return Admission.ADMITTED;
    }

    /** Records that the write request is about to leave: from here on, a failure means an uncertain effect. */
    public void markWriteSent(TrustedStoreScope scope, UUID executionId) {
        jdbc.sql("""
                        UPDATE action_execution SET write_sent = true, updated_at = :now
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("now", Timestamp.from(clock.instant()))
                .param("id", executionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    /**
     * Moves the row to {@code next} when the transition is allowed. A {@code FAILED} row whose write never left gives
     * its limit slot back; an uncertain or applied one keeps it.
     */
    public ActionExecution move(TrustedStoreScope scope, UUID executionId, ExecutionStatus next, String resultCode,
            String providerRequestId) {
        return move(scope, executionId, next, resultCode, providerRequestId, false);
    }

    /** As {@link #move}; {@code nothingChanged} when the provider refused the write, which also gives the slot back. */
    @Transactional
    public ActionExecution move(TrustedStoreScope scope, UUID executionId, ExecutionStatus next, String resultCode,
            String providerRequestId, boolean nothingChanged) {
        ActionExecution current = find(scope, executionId).orElseThrow();
        if (current.status() == next) {
            return current;
        }
        if (!current.status().canMoveTo(next)) {
            throw new IllegalStateException("Execution " + executionId + ": " + current.status() + " -> " + next
                    + " is not allowed");
        }
        Instant now = clock.instant();
        int updated = jdbc.sql("""
                        UPDATE action_execution
                        SET status = :next, result_code = :code,
                            provider_request_id = COALESCE(:providerRequestId, provider_request_id),
                            version = version + 1, updated_at = :now
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                          AND version = :version
                        """)
                .param("next", next.name())
                .param("code", resultCode, java.sql.Types.VARCHAR)
                .param("providerRequestId", providerRequestId, java.sql.Types.VARCHAR)
                .param("now", Timestamp.from(now))
                .param("id", executionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("version", current.version())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Execution " + executionId + " changed concurrently");
        }
        if ((next == ExecutionStatus.FAILED || next == ExecutionStatus.CONFLICT || next == ExecutionStatus.NOOP)
                && (!current.writeSent() || nothingChanged)) {
            jdbc.sql("""
                            UPDATE action_limit_slot SET released_at = :now
                            WHERE execution_id = :id AND organization_id = :organizationId AND store_id = :storeId
                              AND released_at IS NULL
                            """)
                    .param("now", Timestamp.from(now))
                    .param("id", executionId)
                    .param("organizationId", scope.organizationId())
                    .param("storeId", scope.storeId())
                    .update();
        }
        return find(scope, executionId).orElseThrow();
    }

    private Optional<ActionExecution> select(TrustedStoreScope scope, String condition, Object value) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM action_execution "
                        + "WHERE organization_id = :organizationId AND store_id = :storeId AND " + condition)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("value", value)
                .query(ExecutionLedger::map)
                .optional();
    }

    private static ActionExecution map(ResultSet rs, int row) throws SQLException {
        String preState = rs.getString("pre_state");
        return new ActionExecution(rs.getObject("id", UUID.class), rs.getString("idempotency_key"),
                rs.getObject("authorized_action_id", UUID.class), rs.getString("action_type"),
                rs.getString("target_type"), rs.getObject("target_id", UUID.class),
                ExecutionStatus.valueOf(rs.getString("status")), preState == null ? null : CanonicalJson.read(preState),
                rs.getString("request_hash"), rs.getString("provider_request_id"), rs.getString("result_code"),
                rs.getBoolean("write_sent"), rs.getInt("attempts"), rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
