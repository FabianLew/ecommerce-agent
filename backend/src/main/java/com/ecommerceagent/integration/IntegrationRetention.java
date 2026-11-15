package com.ecommerceagent.integration;

import java.sql.Timestamp;
import java.time.Instant;

import com.ecommerceagent.identity.RetentionTask;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Retention of this module's transport rows (ADR-0022 point 5, ADR-0009): webhook inbox rows once handled and
 * outbox events once dispatched. The read models they produced stay; a payload delivered again after the period is
 * judged by the read model's own version checks.
 */
@Configuration(proxyBeanMethods = false)
class IntegrationRetention {

    @Bean
    RetentionTask webhookInboxRetention(JdbcClient jdbc) {
        return new Task("webhook-inbox", jdbc, """
                DELETE FROM webhook_inbox
                WHERE id IN (SELECT id FROM webhook_inbox
                             WHERE status <> 'RECEIVED' AND received_at < :cutoff LIMIT :batch)
                """);
    }

    @Bean
    RetentionTask outboxRetention(JdbcClient jdbc) {
        return new Task("outbox", jdbc, """
                DELETE FROM outbox_event
                WHERE id IN (SELECT id FROM outbox_event
                             WHERE dispatched_at IS NOT NULL AND dispatched_at < :cutoff LIMIT :batch)
                """);
    }

    private record Task(String name, JdbcClient jdbc, String sql) implements RetentionTask {

        @Override
        public int apply(Instant cutoff, int batchSize) {
            return jdbc.sql(sql).param("cutoff", Timestamp.from(cutoff)).param("batch", batchSize).update();
        }
    }
}
