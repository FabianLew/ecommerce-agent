package com.ecommerceagent.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class IdentitySchemaTest {

    @Autowired
    JdbcClient jdbc;

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
    void scopeRequiresAtLeastOneRoleAndIsImmutable() {
        assertThatThrownBy(() -> new TrustedStoreScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        TrustedStoreScope scope = new TrustedStoreScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new java.util.HashSet<>(Set.of(StoreRole.ANALYST)));
        assertThat(scope.hasRole(StoreRole.ANALYST)).isTrue();
        assertThatThrownBy(() -> scope.roles().add(StoreRole.OWNER)).isInstanceOf(UnsupportedOperationException.class);
    }
}
