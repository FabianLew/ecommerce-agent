package com.ecommerceagent.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.identity.Actor.SystemActor;
import com.ecommerceagent.identity.Actor.UserActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Runtime safety (ADR-0004): scopes come only from the trusted factory, carry the actor kind and
 * minimal permissions, and are re-checked against the database before sensitive operations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class StoreAccessTest {

    static final UUID ORG = UUID.fromString("40000000-0000-4000-8000-000000000001");
    static final UUID OTHER_ORG = UUID.fromString("40000000-0000-4000-8000-000000000002");
    static final UUID STORE = UUID.fromString("50000000-0000-4000-8000-00000000000a");
    static final UUID OWNER = UUID.fromString("60000000-0000-4000-8000-0000000000a1");
    static final UUID ANALYST = UUID.fromString("60000000-0000-4000-8000-0000000000b1");

    @Autowired
    StoreAccess storeAccess;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void tenants() {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Org'), (?, 'Other')").params(ORG, OTHER_ORG).update();
        jdbc.sql("INSERT INTO store (id, organization_id, name, currency, timezone) VALUES (?, ?, 'S', 'PLN', 'UTC')")
                .params(STORE, ORG).update();
        jdbc.sql("INSERT INTO app_user (id, issuer, subject) VALUES (?, 'iss', 'owner'), (?, 'iss', 'analyst')")
                .params(OWNER, ANALYST).update();
        jdbc.sql("""
                INSERT INTO store_membership (organization_id, store_id, user_id, role)
                VALUES (?, ?, ?, 'OWNER'), (?, ?, ?, 'ANALYST')
                """).params(ORG, STORE, OWNER, ORG, STORE, ANALYST).update();
    }

    @Test
    void userScopeCarriesActorAndPermissionsFromRoles() {
        TrustedStoreScope owner = storeAccess.scopeFor(OWNER, STORE).orElseThrow();
        TrustedStoreScope analyst = storeAccess.scopeFor(ANALYST, STORE).orElseThrow();

        assertThat(owner.actor()).isEqualTo(new UserActor(OWNER));
        assertThat(owner.permissions()).containsExactly(StorePermission.STORE_READ, StorePermission.STORE_MANAGE);
        assertThat(analyst.permissions()).containsExactly(StorePermission.STORE_READ);
        assertThatThrownBy(() -> analyst.require(StorePermission.STORE_MANAGE))
                .isInstanceOf(StoreAccessDeniedException.class);
        assertThatThrownBy(() -> owner.permissions().add(StorePermission.STORE_MANAGE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void systemScopeRequiresTheStoreInTheGivenOrganizationAndOnlyReads() {
        TrustedStoreScope system = storeAccess.systemScope("inventory-monitor", ORG, STORE).orElseThrow();

        assertThat(system.actor()).isEqualTo(new SystemActor("inventory-monitor"));
        assertThat(system.userId()).isEmpty();
        assertThat(system.permissions()).containsExactly(StorePermission.STORE_READ);
        assertThat(storeAccess.systemScope("inventory-monitor", OTHER_ORG, STORE)).isEmpty();
        assertThat(storeAccess.systemScope("inventory-monitor", ORG, UUID.randomUUID())).isEmpty();
        assertThatThrownBy(() -> new SystemActor("Not A Service")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reauthorizationFailsAfterMembershipIsRevoked() {
        TrustedStoreScope approvedEarlier = storeAccess.scopeFor(OWNER, STORE).orElseThrow();
        assertThat(storeAccess.reauthorize(approvedEarlier, StorePermission.STORE_MANAGE).actor())
                .isEqualTo(new UserActor(OWNER));

        jdbc.sql("DELETE FROM store_membership WHERE user_id = ?").param(OWNER).update();

        assertThatThrownBy(() -> storeAccess.reauthorize(approvedEarlier, StorePermission.STORE_READ))
                .isInstanceOf(StoreAccessDeniedException.class);
    }

    @Test
    void reauthorizationUsesCurrentRolesNotTheOnesInTheScope() {
        TrustedStoreScope approvedEarlier = storeAccess.scopeFor(OWNER, STORE).orElseThrow();

        jdbc.sql("UPDATE store_membership SET role = 'ANALYST' WHERE user_id = ?").param(OWNER).update();

        assertThat(approvedEarlier.has(StorePermission.STORE_MANAGE)).isTrue();
        assertThatThrownBy(() -> storeAccess.reauthorize(approvedEarlier, StorePermission.STORE_MANAGE))
                .isInstanceOf(StoreAccessDeniedException.class);
        assertThat(storeAccess.reauthorize(approvedEarlier, StorePermission.STORE_READ).permissions())
                .containsExactly(StorePermission.STORE_READ);
    }

    @Test
    void systemReauthorizationFailsWhenTheStoreIsGoneAndNeverGrantsManage() {
        TrustedStoreScope system = storeAccess.systemScope("inventory-monitor", ORG, STORE).orElseThrow();

        assertThatThrownBy(() -> storeAccess.reauthorize(system, StorePermission.STORE_MANAGE))
                .isInstanceOf(StoreAccessDeniedException.class);

        jdbc.sql("TRUNCATE store_membership, store CASCADE").update();

        assertThatThrownBy(() -> storeAccess.reauthorize(system, StorePermission.STORE_READ))
                .isInstanceOf(StoreAccessDeniedException.class);
    }
}
