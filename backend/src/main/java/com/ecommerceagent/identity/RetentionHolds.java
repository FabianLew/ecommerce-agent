package com.ecommerceagent.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A legal hold on a store's data (ADR-0022 point 7): while it exists no retention task runs for the store. Only a
 * person who manages the store sets or releases it, and both are audited.
 */
@Service
public class RetentionHolds {

    static final int MAX_REASON = 500;
    private static final String RESOURCE = "store_retention_hold";

    public record Hold(String reason, UUID setBy, Instant setAt) {
    }

    private final StoreAccess storeAccess;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    RetentionHolds(StoreAccess storeAccess, AuditTrail audit, JdbcClient jdbc, Clock clock) {
        this.storeAccess = storeAccess;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<Hold> hold(TrustedStoreScope scope) {
        return jdbc.sql("""
                        SELECT reason, set_by, set_at FROM store_retention_hold
                        WHERE organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query((rs, n) -> new Hold(rs.getString("reason"), rs.getObject("set_by", UUID.class),
                        rs.getTimestamp("set_at").toInstant()))
                .optional();
    }

    /** Sets the hold, or replaces its reason; returns the hold in force. */
    @Transactional
    public Hold set(TrustedStoreScope scope, String reason) {
        String note = reason == null ? "" : reason.strip();
        if (note.isEmpty() || note.length() > MAX_REASON) {
            throw new IllegalArgumentException("A reason of 1 to 500 characters is required");
        }
        TrustedStoreScope fresh = storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE);
        UUID userId = fresh.userId().orElseThrow(() -> new StoreAccessDeniedException(scope.storeId(),
                StorePermission.STORE_MANAGE));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        INSERT INTO store_retention_hold (store_id, organization_id, reason, set_by, set_at)
                        VALUES (:storeId, :organizationId, :reason, :userId, :now)
                        ON CONFLICT (store_id) DO UPDATE SET reason = EXCLUDED.reason, set_by = EXCLUDED.set_by,
                            set_at = EXCLUDED.set_at
                        """)
                .param("storeId", fresh.storeId())
                .param("organizationId", fresh.organizationId())
                .param("reason", note)
                .param("userId", userId)
                .param("now", Timestamp.from(now))
                .update();
        audit.record(AuditEntry.inScope(fresh, "RETENTION_HOLD_SET", RESOURCE, fresh.storeId().toString(),
                Map.of("reason", note)));
        return hold(fresh).orElseThrow();
    }

    /** Releases the hold; false when there was none. */
    @Transactional
    public boolean release(TrustedStoreScope scope) {
        TrustedStoreScope fresh = storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE);
        int deleted = jdbc.sql("""
                        DELETE FROM store_retention_hold WHERE organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("organizationId", fresh.organizationId())
                .param("storeId", fresh.storeId())
                .update();
        if (deleted > 0) {
            audit.record(AuditEntry.inScope(fresh, "RETENTION_HOLD_RELEASED", RESOURCE, fresh.storeId().toString(),
                    Map.of()));
        }
        return deleted > 0;
    }
}
