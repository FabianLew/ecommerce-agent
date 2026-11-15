package com.ecommerceagent.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/** ADR-0022 point 5: each task removes or redacts only what is past its period; recent audit is untouchable. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class RetentionTest {

    @Autowired
    RetentionRunner runner;

    @Autowired
    FixtureDb jdbc;

    @Autowired
    JdbcClient appJdbc;

    @Autowired
    TransactionTemplate transactions;

    UUID organization;
    UUID store;
    Instant now;

    @BeforeEach
    void store() {
        organization = UUID.randomUUID();
        store = UUID.randomUUID();
        now = Instant.now();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Retention')").param(organization).update();
        jdbc.sql("INSERT INTO store (id, organization_id, name, currency, timezone) VALUES (?, ?, 'S', 'PLN', 'UTC')")
                .params(store, organization).update();
    }

    @Test
    void expiredRowsGoAndRecentOnesStay() {
        UUID oldOutbox = outbox(days(40), days(40));
        UUID pendingOutbox = outbox(days(40), null);
        UUID recentOutbox = outbox(days(5), days(5));
        UUID oldAudit = audit(days(500));
        UUID keptAudit = audit(days(380));
        UUID oldNotification = notification(days(200), "SENT");
        UUID openNotification = notification(days(200), "PENDING");
        // ADR-0021: an uncertain delivery still needs reconciliation, so its notification is kept.
        UUID uncertainNotification = notification(days(200), "UNKNOWN");
        UUID exhaustedNotification = notification(days(200), "EXHAUSTED");
        UUID recentNotification = notification(days(10), "SENT");
        UUID redactedRun = agentStep(days(100));
        UUID keptRun = agentStep(days(10));

        Map<String, Integer> changed = runner.runAll();

        assertThat(changed).containsKeys("outbox", "audit", "notifications", "agent-run-content", "webhook-inbox");
        assertThat(exists("outbox_event", oldOutbox)).isFalse();
        assertThat(exists("outbox_event", pendingOutbox)).isTrue();
        assertThat(exists("outbox_event", recentOutbox)).isTrue();
        assertThat(exists("audit_event", oldAudit)).isFalse();
        assertThat(exists("audit_event", keptAudit)).isTrue();
        assertThat(exists("operator_notification", oldNotification)).isFalse();
        assertThat(exists("operator_notification", openNotification)).isTrue();
        assertThat(exists("operator_notification", uncertainNotification)).isTrue();
        assertThat(exists("operator_notification", exhaustedNotification)).isFalse();
        assertThat(exists("operator_notification", recentNotification)).isTrue();
        assertThat(payload(redactedRun)).isEqualTo("{\"redacted\": true}");
        assertThat(payload(keptRun)).contains("prompt");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit_event
                        WHERE action = 'RETENTION_APPLIED' AND actor_service = 'retention' AND resource_id = 'outbox'
                        """).query(Long.class).single()).isPositive();
    }

    @Test
    void theAuditTriggerAllowsOnlyRetentionOfOldEntries() {
        UUID recent = audit(days(30));
        UUID old = audit(days(400));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_event WHERE id = ?").param(old).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.inTransaction(tx -> {
            tx.sql("SELECT set_config('operator.audit_retention', 'on', true)").query(rs -> { });
            tx.sql("DELETE FROM audit_event WHERE id = ?").param(recent).update();
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        jdbc.inTransaction(tx -> {
            tx.sql("SELECT set_config('operator.audit_retention', 'on', true)").query(rs -> { });
            assertThat(tx.sql("DELETE FROM audit_event WHERE id = ?").param(old).update()).isEqualTo(1);
        });
        assertThat(exists("audit_event", recent)).isTrue();
    }

    @Test
    void theRuntimeRoleDeletesAuditOnlyThroughTheRetentionFunctionAndNeverRecentEntries() {
        UUID recent = audit(days(300));
        UUID old = audit(days(400));
        TrustedStoreScope scope = TestScopes.system(organization, store);

        assertThatThrownBy(() -> appJdbc.sql("DELETE FROM audit_event WHERE id = ?").param(old).update())
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.sql("SELECT apply_audit_retention(?, ?, now(), 100)")
                .params(organization, store).query(Integer.class).single())
                .as("only for the bound store")
                .isInstanceOf(DataAccessException.class);
        try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(scope)) {
            Integer deleted = transactions.execute(status -> appJdbc
                    .sql("SELECT apply_audit_retention(?, ?, now(), 100)")
                    .params(organization, store).query(Integer.class).single());
            assertThat(deleted).isEqualTo(1);
        }
        assertThat(exists("audit_event", old)).isFalse();
        assertThat(exists("audit_event", recent)).as("the 365-day floor holds whatever the cutoff").isTrue();
    }

    private Instant days(int days) {
        return now.minus(Duration.ofDays(days));
    }

    private boolean exists(String table, UUID id) {
        String column = table.equals("agent_run_step") ? "run_id" : "id";
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = ?").param(id)
                .query(Long.class).single() > 0;
    }

    private String payload(UUID runId) {
        return jdbc.sql("SELECT payload::text FROM agent_run_step WHERE run_id = ?").param(runId)
                .query(String.class).single();
    }

    private UUID outbox(Instant createdAt, Instant dispatchedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outbox_event (id, organization_id, store_id, event_type, aggregate_type,
                                                  aggregate_id, payload, dedup_key, created_at, dispatched_at,
                                                  next_attempt_at)
                        VALUES (?, ?, ?, 'TEST', 'test', 'a', '{}'::jsonb, ?, ?, ?, ?)
                        """)
                .params(id, organization, store, id.toString(), Timestamp.from(createdAt),
                        dispatchedAt == null ? null : Timestamp.from(dispatchedAt), Timestamp.from(createdAt))
                .update();
        return id;
    }

    private UUID audit(Instant occurredAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO audit_event (id, occurred_at, organization_id, store_id, actor_service, action)
                        VALUES (?, ?, ?, ?, 'retention-test', 'TEST_OLD')
                        """).params(id, Timestamp.from(occurredAt), organization, store).update();
        return id;
    }

    private UUID notification(Instant createdAt, String deliveryStatus) {
        UUID id = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, issuer, subject) VALUES (?, 'iss', ?)").params(user, user.toString())
                .update();
        jdbc.sql("""
                        INSERT INTO operator_notification (id, organization_id, store_id, dedup_key, kind, severity,
                                                           created_at)
                        VALUES (?, ?, ?, ?, 'ACTION_VERIFIED', 'INFO', ?)
                        """).params(id, organization, store, id.toString(), Timestamp.from(createdAt)).update();
        jdbc.sql("""
                        INSERT INTO operator_notification_read (notification_id, organization_id, store_id, user_id,
                                                                read_at)
                        VALUES (?, ?, ?, ?, ?)
                        """).params(id, organization, store, user, Timestamp.from(createdAt)).update();
        jdbc.sql("""
                        INSERT INTO notification_delivery (id, notification_id, organization_id, store_id, channel,
                                                           recipient_user_id, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'EMAIL', ?, ?, ?, ?)
                        """).params(UUID.randomUUID(), id, organization, store, user, deliveryStatus,
                        Timestamp.from(createdAt), Timestamp.from(createdAt)).update();
        return id;
    }

    /** A step without its run: foreign keys are switched off for this insert only (test database superuser). */
    private UUID agentStep(Instant createdAt) {
        UUID runId = UUID.randomUUID();
        jdbc.inTransaction(tx -> {
            tx.sql("SET LOCAL session_replication_role = replica").update();
            tx.sql("""
                            INSERT INTO agent_run_step (run_id, organization_id, store_id, seq, kind, name, status,
                                                        payload, created_at)
                            VALUES (?, ?, ?, 1, 'MODEL_CALL', 'model', 'OK', '{"prompt": "text"}'::jsonb, ?)
                            """).params(runId, organization, store, Timestamp.from(createdAt)).update();
        });
        return runId;
    }
}
