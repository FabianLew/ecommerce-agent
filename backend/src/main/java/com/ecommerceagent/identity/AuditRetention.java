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
    public int apply(Instant cutoff, int batchSize) {
        Instant floor = clock.instant().minus(FLOOR);
        Instant effective = cutoff.isBefore(floor) ? cutoff : floor;
        Integer deleted = transactions.execute(status -> {
            jdbc.sql("SELECT set_config('operator.audit_retention', 'on', true)").query(rs -> { });
            return jdbc.sql("""
                            DELETE FROM audit_event
                            WHERE id IN (SELECT id FROM audit_event WHERE occurred_at < :cutoff LIMIT :batch)
                            """)
                    .param("cutoff", Timestamp.from(effective))
                    .param("batch", batchSize)
                    .update();
        });
        return deleted == null ? 0 : deleted;
    }
}
