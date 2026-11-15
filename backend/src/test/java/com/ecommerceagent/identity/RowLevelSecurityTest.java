package com.ecommerceagent.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR-0022: the database refuses another store's rows even to a query that forgets the store filter, and an
 * unbound connection sees no store rows at all. The queries here are deliberately unfiltered; the application code
 * always filters as well (ADR-0004). {@code jdbc} is the application's own client (the runtime role); fixtures are
 * written as the superuser.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class RowLevelSecurityTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    FixtureDb fixtures;

    @Autowired
    AuditTrail audit;

    @Autowired
    SystemStoreRunner stores;

    UUID organization;
    UUID storeA;
    UUID storeB;

    @BeforeEach
    void stores() {
        organization = UUID.randomUUID();
        storeA = UUID.randomUUID();
        storeB = UUID.randomUUID();
        fixtures.sql("INSERT INTO organization (id, name) VALUES (?, 'RLS')").param(organization).update();
        fixtures.sql("""
                        INSERT INTO store (id, organization_id, name, currency, timezone)
                        VALUES (?, ?, 'A', 'PLN', 'UTC'), (?, ?, 'B', 'PLN', 'UTC')
                        """).params(storeA, organization, storeB, organization).update();
        insertOutbox(fixtures.client(), storeA, "a");
        insertOutbox(fixtures.client(), storeB, "b");
    }

    @Test
    void theApplicationConnectsAsTheRuntimeRole() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("operator_runtime");
        assertThat(jdbc.sql("""
                        SELECT rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb FROM pg_roles
                        WHERE rolname = current_user
                        """).query(Boolean.class).single()).isFalse();
    }

    @Test
    void aBoundScopeSeesAndWritesOnlyItsStore() {
        try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(TestScopes.system(organization, storeA))) {
            assertThat(outboxStores()).containsOnly(storeA);
            assertThatThrownBy(() -> insertOutbox(jdbc, storeB, "b2"))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("row-level security");
            assertThat(jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1 WHERE organization_id = ?")
                    .param(organization).update()).isEqualTo(1);
        }
    }

    @Test
    void unboundWorkSeesAndWritesNoStoreRows() {
        assertThat(StoreRowGuard.current()).isEmpty();
        assertThat(outboxStores()).isEmpty();
        assertThatThrownBy(() -> insertOutbox(jdbc, storeA, "a2"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
        assertThat(jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1 WHERE organization_id = ?")
                .param(organization).update()).isZero();
    }

    @Test
    void aScopeOfAnotherOrganizationSeesNothing() {
        try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(TestScopes.system(UUID.randomUUID(), storeA))) {
            assertThat(outboxStores()).isEmpty();
        }
    }

    @Test
    void theAuditKeepsItsOwnRules() {
        TrustedStoreScope scopeA = TestScopes.system(organization, storeA);
        TrustedStoreScope scopeB = TestScopes.system(organization, storeB);
        try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(scopeB)) {
            audit.record(AuditEntry.inScope(scopeB, "RLS_TEST", "x", "b", Map.of()));
        }
        assertThatThrownBy(() -> audit.record(AuditEntry.inScope(scopeB, "RLS_TEST", "x", "b", Map.of())))
                .as("a store entry needs that store bound")
                .isInstanceOf(DataAccessException.class);
        audit.record(new AuditEntry("RLS_TEST", null, null, UUID.randomUUID(), "x", "platform", Map.of()));
        assertThatThrownBy(() -> audit.record(
                new AuditEntry("RLS_TEST", organization, null, UUID.randomUUID(), "x", "org", Map.of())))
                .as("an organization entry needs that organization bound")
                .isInstanceOf(DataAccessException.class);
        try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(scopeA)) {
            audit.record(AuditEntry.inScope(scopeA, "RLS_TEST", "x", "a", Map.of()));
            audit.record(new AuditEntry("RLS_TEST", organization, null, UUID.randomUUID(), "x", "org", Map.of()));
            assertThat(jdbc.sql("SELECT resource_id FROM audit_event WHERE action = 'RLS_TEST'")
                    .query(String.class).list()).containsExactly("a");
            assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_event WHERE store_id = ?").param(storeA).update())
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("permission denied");
        }
    }

    @Test
    void anotherStoreCannotBeBoundOverABoundStore() {
        try (StoreRowGuard.Binding outer = StoreRowGuard.bind(TestScopes.system(organization, storeA))) {
            try (StoreRowGuard.Binding same = StoreRowGuard.bind(TestScopes.system(organization, storeA))) {
                assertThat(outboxStores()).containsOnly(storeA);
            }
            assertThat(StoreRowGuard.current()).isPresent();
            assertThatThrownBy(() -> StoreRowGuard.bind(TestScopes.system(organization, storeB)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(outboxStores()).containsOnly(storeA);
        }
        assertThat(StoreRowGuard.current()).isEmpty();
    }

    @Test
    void theScopeIsResetWhenTheConnectionIsReturned() throws Exception {
        try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(TestScopes.system(organization, storeA));
             Connection connection = dataSource.getConnection()) {
            assertThat(setting(connection)).isEqualTo(storeA.toString());
        }
        for (int i = 0; i < 20; i++) {
            try (Connection connection = dataSource.getConnection()) {
                assertThat(setting(connection)).as("a pooled connection after a bound one").isEmpty();
            }
        }
    }

    /** Every table with store data forces RLS with a policy; the runtime role owns nothing and is append-only on the audit. */
    @Test
    void everyStoreTableIsProtected() {
        List<String> unprotected = fixtures.sql("""
                        SELECT c.relname
                        FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname <> 'store_membership'
                          AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'store_id')
                          AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity
                               OR NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid
                                              AND 'operator_runtime'::regrole = ANY (p.polroles)))
                        ORDER BY 1
                        """).query(String.class).list();
        assertThat(unprotected).as("store tables without forced row-level security").isEmpty();

        assertThat(fixtures.sql("""
                        SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public' AND c.relowner = 'operator_runtime'::regrole
                        """).query(Long.class).single()).as("objects owned by the runtime role").isZero();

        List<String> missingGrants = fixtures.sql("""
                        SELECT c.relname
                        FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
                          AND NOT (has_table_privilege('operator_runtime', c.oid, 'SELECT')
                                   AND has_table_privilege('operator_runtime', c.oid, 'INSERT'))
                        ORDER BY 1
                        """).query(String.class).list();
        assertThat(missingGrants).as("tables the runtime role cannot use").isEmpty();
        assertThat(fixtures.sql("""
                        SELECT has_table_privilege('operator_runtime', 'public.audit_event', 'UPDATE')
                            OR has_table_privilege('operator_runtime', 'public.audit_event', 'DELETE')
                            OR has_table_privilege('operator_runtime', 'public.flyway_schema_history', 'INSERT')
                            OR has_table_privilege('operator_runtime', 'public.outbox_event', 'TRUNCATE')
                            OR has_schema_privilege('operator_runtime', 'public', 'CREATE')
                        """).query(Boolean.class).single()).isFalse();
        assertThat(fixtures.sql("""
                        SELECT count(*) FROM information_schema.role_table_grants
                        WHERE grantee = 'operator_app'
                        """).query(Long.class).single()).as("privileges left on the retired store role").isZero();
    }

    @Test
    void aSystemJobRunsEveryStoreUnderItsOwnBinding() {
        Map<UUID, List<UUID>> seen = new java.util.HashMap<>();
        int failed = stores.forEachStore("rls-test", scope -> {
            if (scope.organizationId().equals(organization)) {
                seen.put(scope.storeId(), outboxStores());
            }
        });
        assertThat(failed).isZero();
        assertThat(seen).containsOnlyKeys(storeA, storeB);
        assertThat(seen.get(storeA)).containsExactly(storeA);
        assertThat(seen.get(storeB)).containsExactly(storeB);
        assertThat(StoreRowGuard.current()).isEmpty();
    }

    /** ADR-0022 point 4: the reviewed list of definer functions; adding one means revising the ADR and this test. */
    @Test
    void onlyTheReviewedDefinerFunctionsExist() {
        assertThat(fixtures.sql("""
                        SELECT p.proname || ':' || pg_get_userbyid(p.proowner) FROM pg_proc p
                        JOIN pg_namespace n ON n.oid = p.pronamespace
                        WHERE n.nspname = 'public' AND p.prosecdef
                        """).query(String.class).list()).containsExactlyInAnyOrder(
                "webhook_endpoint:operator_system", "operational_metric:operator_system",
                "apply_audit_retention:operator_system", "ai_reserve:operator_system",
                "ai_release:operator_system", "ai_mark_sent:operator_system", "ai_settle:operator_system",
                "ai_mark_unknown:operator_system", "ai_reconcile:operator_system",
                "ai_sweep_reservations:operator_system", "ai_platform_stop_state:operator_system",
                "ai_acquire_run_lease:operator_system", "ai_release_run_lease:operator_system",
                "ai_acquire_provider_slot:operator_system", "ai_release_provider_slot:operator_system",
                "ai_pause_provider:operator_system", "ai_mark_unknown:operator_system",
                "ai_reservation_location:operator_system", "ai_uncertain_reservations:operator_system",
                "ai_adjust_reconciliation:operator_system");
        assertThat(fixtures.sql("""
                        SELECT p.proname FROM pg_proc p
                        WHERE p.proowner = 'operator_system'::regrole AND has_function_privilege('public', p.oid, 'EXECUTE')
                        """).query(String.class).list()).as("no function of operator_system is executable by PUBLIC")
                .isEmpty();
        assertThat(jdbc.sql("SELECT operational_metric('operator.outbox.pending')").query(Double.class).single())
                .as("a cross-store count, unbound").isGreaterThanOrEqualTo(2.0);
        assertThat(fixtures.sql("""
                        SELECT has_schema_privilege('operator_system', 'public', 'CREATE')
                            OR (SELECT rolcanlogin FROM pg_roles WHERE rolname = 'operator_system')
                        """).query(Boolean.class).single()).isFalse();
    }

    private static String setting(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_setting('app.store_id', true)")) {
            result.next();
            String value = result.getString(1);
            return value == null ? "" : value;
        }
    }

    private List<UUID> outboxStores() {
        return jdbc.sql("SELECT store_id FROM outbox_event WHERE organization_id = ?").param(organization)
                .query(UUID.class).list();
    }

    private void insertOutbox(JdbcClient client, UUID store, String key) {
        client.sql("""
                        INSERT INTO outbox_event (id, organization_id, store_id, event_type, aggregate_type,
                                                  aggregate_id, payload, dedup_key, created_at, next_attempt_at)
                        VALUES (?, ?, ?, 'TEST', 'test', ?, '{}'::jsonb, ?, now(), now())
                        """).params(UUID.randomUUID(), organization, store, key, key).update();
    }
}
