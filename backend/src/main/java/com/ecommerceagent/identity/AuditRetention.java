package com.ecommerceagent.identity;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes audit entries past their period (ADR-0004 point 8, ADR-0022 point 5). The audit trigger allows this only
 * in a transaction that set {@code operator.audit_retention} and only for entries older than {@link #FLOOR}, so the
 * cutoff never goes past it.
 */
@Component
class AuditRetention implements RetentionTask {

    static final Duration FLOOR = Duration.ofDays(366);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final java.time.Clock clock;

    AuditRetention(JdbcClient jdbc, TransactionTemplate transactions, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "audit";
    }

    @Override
    public int apply(TrustedStoreScope scope, Instant cutoff, int batchSize) {
        return delete(scope.organizationId(), scope.storeId(), cutoff, batchSize);
    }

    /** Entries without a store (registrations, refused access); runs unbound. */
    int applyWithoutStore(Instant cutoff, int batchSize) {
        return delete(null, null, cutoff, batchSize);
    }

    private int delete(java.util.UUID organizationId, java.util.UUID storeId, Instant cutoff, int batchSize) {
        Instant floor = clock.instant().minus(FLOOR);
        Instant effective = cutoff.isBefore(floor) ? cutoff : floor;
        // The definer function is the only way to delete audit entries; it enforces the floor itself (ADR-0022).
        Integer deleted = transactions.execute(status -> {
            return jdbc.sql("SELECT apply_audit_retention(:organizationId, :storeId, :cutoff, :batch)")
                    .param("organizationId", organizationId, java.sql.Types.OTHER)
                    .param("storeId", storeId, java.sql.Types.OTHER)
                    .param("cutoff", Timestamp.from(effective))
                    .param("batch", batchSize)
                    .query(Integer.class).single();
        });
        return deleted == null ? 0 : deleted;
    }
}
