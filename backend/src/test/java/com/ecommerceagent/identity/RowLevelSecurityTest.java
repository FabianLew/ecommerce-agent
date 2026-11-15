package com.ecommerceagent.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ADR-0022: the database refuses another store's rows even to a query that forgets the store filter. The query
 * here is deliberately unfiltered; the application code always filters as well (ADR-0004).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class RowLevelSecurityTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AuditTrail audit;

    UUID organization;
    UUID storeA;
    UUID storeB;

    @BeforeEach
    void stores() {
        organization = UUID.randomUUID();
        storeA = UUID.randomUUID();
        storeB = UUID.randomUUID();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'RLS')").param(organization).update();
        jdbc.sql("""
                        INSERT INTO store (id, organization_id, name, currency, timezone)
                        VALUES (?, ?, 'A', 'PLN', 'UTC'), (?, ?, 'B', 'PLN', 'UTC')
                        """).params(storeA, organization, storeB, organization).update();
        insertOutbox(storeA, "a");
        insertOutbox(storeB, "b");
    }

    @Test
    void aBoundScopeSeesAndWritesOnlyItsStore() {
        try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(TestScopes.system(organization, storeA))) {
            assertThat(outboxStores()).containsOnly(storeA);
            assertThatThrownBy(() -> insertOutbox(storeB, "b2"))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("row-level security");
            assertThat(jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1 WHERE organization_id = ?")
                    .param(organization).update()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                    .isEqualTo(RowSecurityDataSource.STORE_ROLE);
        }
        assertThat(outboxStores()).containsExactlyInAnyOrder(storeA, storeB);
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                .isNotEqualTo(RowSecurityDataSource.STORE_ROLE);
    }

    @Test
    void aScopeOfAnotherOrganizationSeesNothing() {
        try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(TestScopes.system(UUID.randomUUID(), storeA))) {
            assertThat(outboxStores()).isEmpty();
        }
    }

    @Test
    void theAuditKeepsItsOwnRulesUnderTheStoreRole() {
        TrustedStoreScope scopeA = TestScopes.system(organization, storeA);
        audit.record(AuditEntry.inScope(TestScopes.system(organization, storeB), "RLS_TEST", "x", "b", Map.of()));
        try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(scopeA)) {
            audit.record(AuditEntry.inScope(scopeA, "RLS_TEST", "x", "a", Map.of()));
            audit.record(new AuditEntry("RLS_TEST", null, null, UUID.randomUUID(), "x", "none", Map.of()));
            assertThat(jdbc.sql("SELECT resource_id FROM audit_event WHERE action = 'RLS_TEST'")
                    .query(String.class).list()).containsExactly("a");
            assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_event WHERE store_id = ?").param(storeA).update())
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("permission denied");
        }
    }

    @Test
    void nestedBindingsRestoreTheOuterScope() {
        try (StoreRowGuard.Binding outer = StoreRowGuard.bind(TestScopes.system(organization, storeA))) {
            try (StoreRowGuard.Binding inner = StoreRowGuard.bind(TestScopes.system(organization, storeB))) {
                assertThat(outboxStores()).containsOnly(storeB);
            }
            assertThat(outboxStores()).containsOnly(storeA);
        }
        assertThat(StoreRowGuard.current()).isEmpty();
    }

    /** Every table with store data has RLS and a policy, and the store role has the expected privileges. */
    @Test
    void everyStoreTableIsProtected() {
        List<String> unprotected = jdbc.sql("""
                        SELECT c.relname
                        FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public' AND c.relkind = 'r'
                          AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'store_id')
                          AND (NOT c.relrowsecurity
                               OR NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid))
                        ORDER BY 1
                        """).query(String.class).list();
        assertThat(unprotected).as("store tables without row-level security").isEmpty();

        List<String> missingGrants = jdbc.sql("""
                        SELECT c.relname
                        FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
                          AND NOT (has_table_privilege('operator_app', c.oid, 'SELECT')
                                   AND has_table_privilege('operator_app', c.oid, 'INSERT'))
                        ORDER BY 1
                        """).query(String.class).list();
        assertThat(missingGrants).as("tables the store role cannot use").isEmpty();
        assertThat(jdbc.sql("""
                        SELECT has_table_privilege('operator_app', 'public.audit_event', 'UPDATE')
                            OR has_table_privilege('operator_app', 'public.audit_event', 'DELETE')
                        """).query(Boolean.class).single()).isFalse();
    }

    private List<UUID> outboxStores() {
        return jdbc.sql("SELECT store_id FROM outbox_event WHERE organization_id = ?").param(organization)
                .query(UUID.class).list();
    }

    private void insertOutbox(UUID store, String key) {
        jdbc.sql("""
                        INSERT INTO outbox_event (id, organization_id, store_id, event_type, aggregate_type,
                                                  aggregate_id, payload, dedup_key, created_at, next_attempt_at)
                        VALUES (?, ?, ?, 'TEST', 'test', ?, '{}'::jsonb, ?, now(), now())
                        """).params(UUID.randomUUID(), organization, store, key, key).update();
    }
}
