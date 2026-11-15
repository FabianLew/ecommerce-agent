package com.ecommerceagent.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class IdentitySchemaTest {

    @Autowired
    FixtureDb jdbc;

    @Autowired
    AuditTrail audit;


    @Test
    void auditEventsCannotBeChangedOrDeleted() {
        UUID actor = UUID.randomUUID();
        audit.record(new AuditEntry("TEST_EVENT", null, null, actor, "test", "1", java.util.Map.of("k", "v")));

        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_event SET action = 'X' WHERE actor_user_id = ?")
                .param(actor).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_event WHERE actor_user_id = ?").param(actor).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(jdbc.sql("SELECT details->>'k' FROM audit_event WHERE actor_user_id = ?")
                .param(actor).query(String.class).single()).isEqualTo("v");
    }

    @Test
    void membershipMustMatchTheStoresOrganization() {
        UUID org = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'O1'), (?, 'O2')").params(org, otherOrg).update();
        jdbc.sql("INSERT INTO store (id, organization_id, name, currency, timezone) VALUES (?, ?, 'S', 'PLN', 'UTC')")
                .params(store, org).update();
        jdbc.sql("INSERT INTO app_user (id, issuer, subject) VALUES (?, 'iss', ?)").params(user, user.toString()).update();

        assertThatThrownBy(() -> jdbc.sql(
                        "INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES (?, ?, ?, 'OWNER')")
                .params(otherOrg, store, user).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void auditEntryHasEitherAUserOrASystemActor() {
        audit.record(new AuditEntry("TEST_SYSTEM_EVENT", null, null, null, "inventory-monitor", "test", "1", null));

        assertThatThrownBy(() -> new AuditEntry("X", null, null, UUID.randomUUID(), "inventory-monitor", null, null,
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO audit_event (id, occurred_at, actor_user_id, actor_service, action)
                        VALUES (?, now(), ?, 'svc', 'X')
                        """).params(UUID.randomUUID(), UUID.randomUUID()).update())
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * ADR-0004: every store-scoped table references store (id, organization_id) with a composite foreign
     * key, so a row can never pair a store with another organization. New tables are checked automatically.
     */
    @Test
    void everyStoreScopedTableHasCompositeStoreForeignKey() {
        assertThat(storeScopedTables()).contains("store_membership");
        assertThat(tablesMissingCompositeStoreKey()).isEmpty();
    }

    @Test
    void compositeKeyGuardDetectsAViolatingTable() {
        jdbc.rolledBack(tx -> {
            tx.sql("CREATE TABLE guard_probe (organization_id uuid, store_id uuid REFERENCES store (id))").update();
            assertThat(tablesMissingCompositeStoreKey(tx)).containsExactly("guard_probe");
        });
        assertThat(tablesMissingCompositeStoreKey()).isEmpty();
    }

    private List<String> storeScopedTables() {
        return jdbc.sql("""
                        SELECT c.table_name FROM information_schema.columns c
                        JOIN information_schema.tables t
                          ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                        WHERE c.table_schema = current_schema() AND c.column_name = 'store_id'
                          AND t.table_type = 'BASE TABLE'
                        ORDER BY 1
                        """).query(String.class).list();
    }

    private List<String> tablesMissingCompositeStoreKey() {
        return tablesMissingCompositeStoreKey(jdbc.client());
    }

    private static List<String> tablesMissingCompositeStoreKey(JdbcClient db) {
        return db.sql("""
                        SELECT c.table_name FROM information_schema.columns c
                        JOIN information_schema.tables t
                          ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                        WHERE c.table_schema = current_schema() AND c.column_name = 'store_id'
                          AND t.table_type = 'BASE TABLE'
                          AND c.table_name NOT IN ('audit_event')
                          AND NOT EXISTS (
                            SELECT 1 FROM pg_constraint k
                            WHERE k.contype = 'f'
                              AND k.conrelid = to_regclass(quote_ident(c.table_name))
                              AND k.confrelid = 'store'::regclass
                              AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
                                   FROM unnest(k.conkey) AS n(attnum)
                                   JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = n.attnum)
                                  = ARRAY['organization_id', 'store_id'])
                        ORDER BY 1
                        """).query(String.class).list();
    }
}
