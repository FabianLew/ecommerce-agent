package com.ecommerceagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.execution.AdPauseExecutor;
import com.ecommerceagent.automation.execution.AuthorizedAction;
import com.ecommerceagent.automation.execution.ExecutionAuthority;
import com.ecommerceagent.automation.execution.ExecutionStatus;
import com.ecommerceagent.automation.guardian.DecisionApprovals;
import com.ecommerceagent.automation.guardian.GuardianSignals;
import com.ecommerceagent.commerce.AdData;
import com.ecommerceagent.commerce.AdMappingScope;
import com.ecommerceagent.commerce.AdSource;
import com.ecommerceagent.commerce.AdStatus;
import com.ecommerceagent.commerce.MappingVerification;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.MetaConnections;
import com.ecommerceagent.integration.meta.GraphApiStub;
import com.ecommerceagent.integration.meta.GraphApiStub.Reply;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The pause executor (B-39, ADR-0020 point 6) against the Graph API stub: one write per admitted attempt, after a
 * fresh read that matches the authorization; reconciliation instead of a second write; verification by reading.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"api", "test"})
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"operator.policies.inventory-protection.mode=EXECUTE_ON_APPROVAL",
        "operator.integration.meta.timeout=1s", "operator.integration.meta.poller-enabled=false"})
class AdPauseExecutorTest extends DecisionScenario {

    static final GraphApiStub GRAPH;

    static {
        try {
            GRAPH = new GraphApiStub();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void graph(DynamicPropertyRegistry registry) {
        registry.add("operator.integration.meta.graph-base-url", () -> GRAPH.baseUrl().toString());
    }

    @AfterAll
    static void stopGraph() {
        GRAPH.close();
    }

    @MockitoBean
    GuardianSignals signals;
    @MockitoSpyBean
    Clock spiedClock;
    @Autowired
    DecisionApprovals approvals;
    @Autowired
    ExecutionAuthority authority;
    @Autowired
    AdPauseExecutor executor;
    @Autowired
    MetaConnections connections;

    TrustedStoreScope owner;

    static Reply ad(String status) {
        return Reply.ok("{\"id\":\"ad-9001\",\"name\":\"Serum dedicated\",\"status\":\"" + status
                + "\",\"effective_status\":\"" + status + "\",\"creative\":{\"id\":\"44\"}}");
    }

    @BeforeEach
    void metaConnected() {
        GRAPH.reset();
        owner = storeAccess.scopeFor(OWNER, store).orElseThrow();
        UUID connection = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO meta_connection (id, organization_id, store_id, account_id, status, can_write, created_at,
                                             updated_at)
                VALUES (?, ?, ?, '1234567890', 'CONNECTED', true, now(), now())
                """).params(connection, ORG, store).update();
        connections.saveToken(owner, connection, GraphApiStub.TOKEN);
        jdbc.sql("UPDATE meta_connection SET status = 'CONNECTED', can_write = true WHERE id = ?")
                .param(connection).update();
        // The synced ad carries Meta's creative fingerprint, as polling stores it.
        ads.upsertAd(scope, new AdData("ad-9001", "act-01", "Serum dedicated", AdStatus.ACTIVE, "ACTIVE",
                "meta-creative:44", clock.instant(), AdSource.FIXTURE));
        ads.saveMapping(scope, dedicatedAd, AdMappingScope.SINGLE_VARIANT, List.of(serum),
                MappingVerification.VERIFIED, clock.instant());
    }

    AuthorizedAction authorized() {
        UUID proposalId = awaitingDecision();
        UUID approvalId = approvals.approve(owner, proposalId, 1, "k-" + proposalId).approval().id();
        ExecutionAuthority.Authorization authorization = authority.authorize(scope, approvalId);
        assertThat(authorization.refusal()).isNull();
        return authorization.action();
    }

    long writes() {
        return GRAPH.requests.stream().filter(r -> r.method().equals("POST")).count();
    }

    @Test
    void anActiveAdIsPausedOnceAndVerifiedByReading() {
        AuthorizedAction action = authorized();
        GRAPH.on("GET ad-9001", ad("ACTIVE"));
        GRAPH.on("POST ad-9001", Reply.ok("{\"success\":true}"));

        AdPauseExecutor.Outcome outcome = executor.execute(scope, action.id());

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.APPLIED);
        assertThat(writes()).isEqualTo(1);
        assertThat(GRAPH.requests.stream().filter(r -> r.method().equals("POST")).findFirst().orElseThrow().body())
                .isEqualTo("status=PAUSED");
        // A repeated attempt returns the ledger row and never writes again.
        assertThat(executor.execute(scope, action.id()).status()).isEqualTo(ExecutionStatus.APPLIED);
        assertThat(writes()).isEqualTo(1);

        assertThat(executor.verify(scope, outcome.executionId(), false).code()).isEqualTo("NOT_YET_PAUSED");
        GRAPH.on("GET ad-9001", ad("PAUSED"));
        assertThat(executor.verify(scope, outcome.executionId(), false).status()).isEqualTo(ExecutionStatus.VERIFIED);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE store_id = ? AND action = 'ACTION_VERIFIED'")
                .param(store).query(Long.class).single()).isEqualTo(1);
        assertThat(GRAPH.requests).allSatisfy(r -> assertThat(r.uri().toString())
                .doesNotContain(GraphApiStub.TOKEN));
    }

    @Test
    void anAlreadyPausedAdIsANoopWithoutAWrite() {
        AuthorizedAction action = authorized();
        GRAPH.on("GET ad-9001", ad("PAUSED"));

        AdPauseExecutor.Outcome outcome = executor.execute(scope, action.id());

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.NOOP);
        assertThat(outcome.code()).isEqualTo("ALREADY_PAUSED");
        assertThat(writes()).isZero();
        assertThat(openSlots()).isZero();
    }

    @Test
    void aChangedCreativeIsAConflict() {
        AuthorizedAction action = authorized();
        GRAPH.on("GET ad-9001", Reply.ok("{\"id\":\"ad-9001\",\"status\":\"ACTIVE\",\"effective_status\":\"ACTIVE\","
                + "\"creative\":{\"id\":\"45\"}}"));

        AdPauseExecutor.Outcome outcome = executor.execute(scope, action.id());

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.CONFLICT);
        assertThat(outcome.code()).isEqualTo("CREATIVE_CHANGED");
        assertThat(writes()).isZero();
    }

    @Test
    void aTimeoutAfterTheWriteIsReconciledByReadingNotByWritingAgain() {
        AuthorizedAction action = authorized();
        GRAPH.on("GET ad-9001", ad("ACTIVE"));
        GRAPH.on("POST ad-9001", Reply.ok("{\"success\":true}").delayed(2_000));

        AdPauseExecutor.Outcome first = executor.execute(scope, action.id());
        assertThat(first.status()).isEqualTo(ExecutionStatus.UNKNOWN);
        assertThat(first.code()).isEqualTo("META_UNCERTAIN");
        // UNKNOWN keeps its limit slot.
        assertThat(openSlots()).isEqualTo(1);

        GRAPH.on("GET ad-9001", ad("PAUSED"));
        AdPauseExecutor.Outcome retry = executor.execute(scope, action.id());

        assertThat(retry.status()).isEqualTo(ExecutionStatus.APPLIED);
        assertThat(retry.code()).isEqualTo("EFFECT_FOUND_ON_RECONCILIATION");
        assertThat(writes()).isEqualTo(1);
    }

    @Test
    void aServerErrorOnTheWriteIsUncertain() {
        AuthorizedAction action = authorized();
        GRAPH.on("GET ad-9001", ad("ACTIVE"));
        GRAPH.on("POST ad-9001", Reply.error(500, 2, 0));

        assertThat(executor.execute(scope, action.id()).status()).isEqualTo(ExecutionStatus.UNKNOWN);
    }

    @Test
    void anExpiredAuthorizationNeverWrites() {
        AuthorizedAction action = authorized();
        // authorized_action is immutable: the clock moves past the 60 s validity instead.
        doReturn(action.expiresAt().plusSeconds(1)).when(spiedClock).instant();
        GRAPH.on("GET ad-9001", ad("ACTIVE"));
        GRAPH.on("POST ad-9001", Reply.ok("{\"success\":true}"));

        AdPauseExecutor.Outcome outcome = executor.execute(scope, action.id());

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(outcome.code()).isEqualTo("AUTHORIZATION_EXPIRED");
        assertThat(writes()).isZero();
    }

    @Test
    void approverAccessRevokedBeforeTheWriteStopsIt() {
        AuthorizedAction action = authorized();
        jdbc.sql("UPDATE store_membership SET role = 'ANALYST' WHERE user_id = ? AND store_id = ?")
                .params(OWNER, store).update();
        GRAPH.on("GET ad-9001", ad("ACTIVE"));
        GRAPH.on("POST ad-9001", Reply.ok("{\"success\":true}"));

        AdPauseExecutor.Outcome outcome = executor.execute(scope, action.id());

        assertThat(outcome.code()).isEqualTo("APPROVER_ACCESS_REVOKED");
        assertThat(writes()).isZero();
    }

    @Test
    void aBusyTargetIsRetryableWithoutARead() {
        AuthorizedAction action = authorized();
        jdbc.sql("""
                INSERT INTO action_target_lease (organization_id, store_id, target_type, target_id, owner, lease_until)
                VALUES (?, ?, 'META_AD', ?, 'someone-else', now() + interval '1 minute')
                """).params(ORG, store, dedicatedAd).update();

        AdPauseExecutor.Outcome outcome = executor.execute(scope, action.id());

        assertThat(outcome.code()).isEqualTo("TARGET_BUSY");
        assertThat(outcome.retryable()).isTrue();
        assertThat(GRAPH.requests).isEmpty();
    }

    long openSlots() {
        return jdbc.sql("SELECT count(*) FROM action_limit_slot WHERE store_id = ? AND released_at IS NULL")
                .param(store).query(Long.class).single();
    }
}
