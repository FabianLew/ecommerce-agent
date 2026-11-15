package com.ecommerceagent.automation.notifications;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.identity.RetentionTask;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes old notifications with their read marks and deliveries (ADR-0022 point 5). A notification with a delivery
 * still {@code PENDING} or {@code SENDING} is kept until the sweep finishes it.
 */
@Component
class NotificationRetention implements RetentionTask {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    NotificationRetention(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public String name() {
        return "notifications";
    }

    @Override
    public int apply(Instant cutoff, int batchSize) {
        Integer deleted = transactions.execute(status -> {
            List<UUID> ids = jdbc.sql("""
                            SELECT n.id FROM operator_notification n
                            WHERE n.created_at < :cutoff
                              AND NOT EXISTS (SELECT 1 FROM notification_delivery d
                                              WHERE d.notification_id = n.id AND d.status IN ('PENDING', 'SENDING'))
                            LIMIT :batch
                            FOR UPDATE SKIP LOCKED
                            """)
                    .param("cutoff", Timestamp.from(cutoff))
                    .param("batch", batchSize)
                    .query(UUID.class).list();
            if (ids.isEmpty()) {
                return 0;
            }
            jdbc.sql("DELETE FROM operator_notification_read WHERE notification_id IN (:ids)").param("ids", ids)
                    .update();
            jdbc.sql("DELETE FROM notification_delivery WHERE notification_id IN (:ids)").param("ids", ids).update();
            return jdbc.sql("DELETE FROM operator_notification WHERE id IN (:ids)").param("ids", ids).update();
        });
        return deleted == null ? 0 : deleted;
    }
}
