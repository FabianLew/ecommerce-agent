package com.ecommerceagent.automation.execution;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StoreAccessDeniedException;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether external mutations may run (ADR-0020 point 7): the operators' global setting and the store's own stop.
 * Checked when approving, authorizing and right before a write; never for reads, verification or reconciliation.
 */
@Service
@NamedInterface("execution")
public class MutationStops {

    public static final String GLOBAL = "MUTATIONS_STOPPED_GLOBALLY";
    public static final String STORE = "MUTATIONS_STOPPED";
    static final int MAX_REASON = 500;
    private static final String RESOURCE = "store_mutation_stop";

    /** The store's stop as stored; version 0 when nobody has changed it yet. */
    public record State(boolean stopped, String reason, int version, UUID changedBy, Instant changedAt,
            boolean globallyStopped) {
    }

    private final ExecutionSettings settings;
    private final StoreAccess storeAccess;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    MutationStops(ExecutionSettings settings, StoreAccess storeAccess, AuditTrail audit, JdbcClient jdbc,
            Clock clock) {
        this.settings = settings;
        this.storeAccess = storeAccess;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The reason mutations are stopped for the store, or empty when they may run. */
    public Optional<String> blocker(TrustedStoreScope scope) {
        if (!settings.mutationsEnabled()) {
            return Optional.of(GLOBAL);
        }
        return state(scope).stopped() ? Optional.of(STORE) : Optional.empty();
    }

    public State state(TrustedStoreScope scope) {
        return jdbc.sql("""
                        SELECT stopped, reason, version, changed_by, changed_at FROM store_mutation_stop
                        WHERE organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query((rs, n) -> new State(rs.getBoolean("stopped"), rs.getString("reason"), rs.getInt("version"),
                        rs.getObject("changed_by", UUID.class), rs.getTimestamp("changed_at").toInstant(),
                        !settings.mutationsEnabled()))
                .optional()
                .orElse(new State(false, null, 0, null, null, !settings.mutationsEnabled()));
    }

    /** Who may stop: a person who approves decisions or manages the store. Resuming needs the latter. */
    public static boolean mayStop(TrustedStoreScope scope) {
        return scope.has(StorePermission.DECISION_APPROVE) || scope.has(StorePermission.STORE_MANAGE);
    }

    /**
     * Stops or resumes mutations for the store, only from the version the person saw. Stopping takes effect for
     * every write not yet sent; a write already sent is still verified and reconciled.
     */
    @Transactional
    public State change(TrustedStoreScope scope, boolean stopped, String reason, int expectedVersion) {
        String note = reason == null || reason.isBlank() ? null : reason.strip();
        if (stopped && note == null) {
            throw new MutationStopException(MutationStopException.Kind.INVALID, "REASON_REQUIRED");
        }
        if (note != null && note.length() > MAX_REASON) {
            throw new MutationStopException(MutationStopException.Kind.INVALID, "REASON_TOO_LONG");
        }
        // Both directions re-check the membership; resuming is the riskier one and needs STORE_MANAGE (ADR-0004).
        TrustedStoreScope fresh = stopped ? reauthorizeStop(scope)
                : storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE);
        UUID userId = fresh.userId().orElseThrow(() -> new StoreAccessDeniedException(scope.storeId(),
                StorePermission.STORE_MANAGE));
        // Serialize changes per store so that two people cannot both pass the version check.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lock, 0))")
                .param("lock", "mutation-stop:" + fresh.storeId())
                .query(rs -> {
                });
        State current = state(fresh);
        if (current.version() != expectedVersion) {
            throw new MutationStopException(MutationStopException.Kind.PRECONDITION_FAILED, "VERSION_CHANGED");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int version = current.version() + 1;
        jdbc.sql("""
                        INSERT INTO store_mutation_stop (store_id, organization_id, stopped, reason, version,
                                                         changed_by, changed_at)
                        VALUES (:storeId, :organizationId, :stopped, :reason, :version, :userId, :now)
                        ON CONFLICT (store_id) DO UPDATE SET stopped = EXCLUDED.stopped, reason = EXCLUDED.reason,
                            version = EXCLUDED.version, changed_by = EXCLUDED.changed_by,
                            changed_at = EXCLUDED.changed_at
                        """)
                .param("storeId", fresh.storeId())
                .param("organizationId", fresh.organizationId())
                .param("stopped", stopped)
                .param("reason", note, java.sql.Types.VARCHAR)
                .param("version", version)
                .param("userId", userId)
                .param("now", Timestamp.from(now))
                .update();
        audit.record(AuditEntry.inScope(fresh, stopped ? "MUTATIONS_STOPPED" : "MUTATIONS_RESUMED", RESOURCE,
                fresh.storeId().toString(), Map.of("version", Integer.toString(version), "reason",
                        note == null ? "" : note)));
        return state(fresh);
    }

    private TrustedStoreScope reauthorizeStop(TrustedStoreScope scope) {
        try {
            return storeAccess.reauthorize(scope, StorePermission.DECISION_APPROVE);
        } catch (StoreAccessDeniedException e) {
            return storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE);
        }
    }
}
