package com.ecommerceagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.execution.ActionOutcomes;
import com.ecommerceagent.automation.execution.AdPauseExecutor;
import com.ecommerceagent.automation.execution.AuthorizedAction;
import com.ecommerceagent.automation.execution.ExecutionAuthority;
import com.ecommerceagent.automation.execution.ExecutionStatus;
import com.ecommerceagent.automation.execution.MutationStops;
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
    @Autowired
    MutationStops mutationStops;
    @Autowired
    ActionOutcomes outcomes;

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
    void aMutationStopBetweenApprovalAndWriteKeepsTheWriteFromHappening() {
        // IG-14: the stop arrives after the authorization was issued.
        AuthorizedAction action = authorized();
        mutationStops.change(owner, true, "Account under review", 0);
        GRAPH.on("GET ad-9001", ad("ACTIVE"));
        GRAPH.on("POST ad-9001", Reply.ok("{\"success\":true}"));

        AdPauseExecutor.Outcome outcome = executor.execute(scope, action.id());

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(outcome.code()).isEqualTo("MUTATIONS_STOPPED");
        assertThat(writes()).isZero();
        assertThat(openSlots()).isZero();
    }

    @Test
    void aStopNeverBlocksReconcilingAWriteAlreadySent() {
        AuthorizedAction action = authorized();
        GRAPH.on("GET ad-9001", ad("ACTIVE"));
        GRAPH.on("POST ad-9001", Reply.error(503, 2, 0));
        assertThat(executor.execute(scope, action.id()).status()).isEqualTo(ExecutionStatus.UNKNOWN);
        mutationStops.change(owner, true, "Stop everything", 0);

        // The ad turned out paused: the effect is confirmed by reading and verified, no new write.
        GRAPH.on("GET ad-9001", ad("PAUSED"));
        AdPauseExecutor.Outcome retry = executor.execute(scope, action.id());
        assertThat(retry.status()).isEqualTo(ExecutionStatus.APPLIED);
        assertThat(executor.verify(scope, retry.executionId(), false).status()).isEqualTo(ExecutionStatus.VERIFIED);
        assertThat(writes()).isEqualTo(1);
    }

    @Test
    void aStopKeepsAnUncertainRetryFromWritingAgain() {
        AuthorizedAction action = authorized();
        GRAPH.on("GET ad-9001", ad("ACTIVE"));
        GRAPH.on("POST ad-9001", Reply.error(503, 2, 0));
        assertThat(executor.execute(scope, action.id()).status()).isEqualTo(ExecutionStatus.UNKNOWN);
        mutationStops.change(owner, true, "Stop everything", 0);

        // Still active after the uncertain write: a new write would be needed, and the stop forbids it.
        AdPauseExecutor.Outcome retry = executor.execute(scope, action.id());
        assertThat(retry.status()).isEqualTo(ExecutionStatus.UNKNOWN);
        assertThat(retry.code()).isEqualTo("MUTATIONS_STOPPED");
        assertThat(writes()).isEqualTo(1);
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

    AdPauseExecutor.Outcome pausedAndVerified(AuthorizedAction action) {
        GRAPH.on("GET ad-9001", ad("ACTIVE"));
        GRAPH.on("POST ad-9001", exchange -> {
            GRAPH.on("GET ad-9001", ad("PAUSED"));
            return Reply.ok("{\"success\":true}");
        });
        AdPauseExecutor.Outcome applied = executor.execute(scope, action.id());
        return executor.verify(scope, applied.executionId(), false);
    }

    @Test
    void aVerifiedPauseGetsAnOutcomeAndCheckpointsThatNeverClaimSuccess() {
        AuthorizedAction action = authorized();
        AdPauseExecutor.Outcome verified = pausedAndVerified(action);
        assertThat(verified.status()).isEqualTo(ExecutionStatus.VERIFIED);

        ActionOutcomes.Outcome outcome = outcomes.record(scope, verified.executionId());
        assertThat(outcomes.record(scope, verified.executionId()).id()).isEqualTo(outcome.id());
        assertThat(outcome.technicalResult()).isEqualTo(ExecutionStatus.VERIFIED);
        assertThat(outcome.authorship()).isEqualTo(ActionOutcomes.Authorship.OPERATOR);
        assertThat(outcome.preState().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(outcome.postState().path("status").asString()).isEqualTo("PAUSED");
        assertThat(outcome.variantId()).isEqualTo(serum);
        assertThat(outcome.observations()).extracting(ActionOutcomes.Observation::checkpoint)
                .containsExactly(ActionOutcomes.Checkpoint.H24, ActionOutcomes.Checkpoint.D7);
        assertThat(outcome.observations()).allMatch(o -> o.status() == ActionOutcomes.ObservationStatus.PENDING);

        // Two hours later 6 units were sold and 12 are left; orders are synchronized through the checkpoint.
        java.time.Instant at = outcome.verifiedAt();
        inventory.record(scope, serum, new com.ecommerceagent.commerce.InventoryReading(12,
                com.ecommerceagent.commerce.StockStatus.IN_STOCK,
                com.ecommerceagent.commerce.ReservationSemantics.ALREADY_ACCOUNTED_FOR, null,
                com.ecommerceagent.commerce.ObservationSource.WEBHOOK, at.plusSeconds(7200), at.plusSeconds(7200)));
        java.time.Instant placed = at.plusSeconds(3600);
        orders.upsert(scope, com.ecommerceagent.commerce.CommerceSource.WOOCOMMERCE,
                new com.ecommerceagent.commerce.OrderData("o-after", "processing", "PLN", new java.math.BigDecimal(
                        "600.00"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                        placed, placed, null, placed, List.of(new com.ecommerceagent.commerce.OrderLineData("l-after",
                                "826", "812", "SERUM-30", 6, new java.math.BigDecimal("100.00"),
                                java.math.BigDecimal.ZERO))));
        orders.recordCoverage(scope, com.ecommerceagent.commerce.CommerceSource.WOOCOMMERCE,
                at.minusSeconds(86_400), at.plusSeconds(2 * 86_400));
        doReturn(at.plusSeconds(25 * 3600)).when(spiedClock).instant();

        assertThat(outcomes.observeDue(10)).isEqualTo(1);

        ActionOutcomes.Observation day = outcomes.ofExecution(scope, verified.executionId()).orElseThrow()
                .observations().getFirst();
        assertThat(day.status()).isEqualTo(ActionOutcomes.ObservationStatus.OBSERVED);
        assertThat(day.values().path("stockAtVerification").asInt()).isEqualTo(18);
        assertThat(day.values().path("stockAtCheckpoint").asInt()).isEqualTo(12);
        assertThat(day.values().path("unitsSold").asLong()).isEqualTo(6);
        assertThat(day.limitations()).containsExactly("ATTRIBUTION_NOT_CAUSAL", "SPEND_NOT_AVAILABLE");
        // The seven-day checkpoint is not due yet.
        assertThat(outcomes.ofExecution(scope, verified.executionId()).orElseThrow().observations().getLast()
                .status()).isEqualTo(ActionOutcomes.ObservationStatus.PENDING);
    }

    @Test
    void aDeliveryCensorsTheStockAndMissingOrdersMakeItInconclusive() {
        AuthorizedAction action = authorized();
        AdPauseExecutor.Outcome verified = pausedAndVerified(action);
        java.time.Instant at = outcomes.record(scope, verified.executionId()).verifiedAt();
        inventory.record(scope, serum, new com.ecommerceagent.commerce.InventoryReading(60,
                com.ecommerceagent.commerce.StockStatus.IN_STOCK,
                com.ecommerceagent.commerce.ReservationSemantics.ALREADY_ACCOUNTED_FOR, null,
                com.ecommerceagent.commerce.ObservationSource.WEBHOOK, at.plusSeconds(3600), at.plusSeconds(3600)));
        doReturn(at.plusSeconds(25 * 3600)).when(spiedClock).instant();

        outcomes.observeDue(10);

        ActionOutcomes.Observation day = outcomes.ofExecution(scope, verified.executionId()).orElseThrow()
                .observations().getFirst();
        assertThat(day.status()).isEqualTo(ActionOutcomes.ObservationStatus.INCONCLUSIVE);
        assertThat(day.limitations()).contains("STOCK_INCREASED_IN_WINDOW", "ORDERS_NOT_COVERED");
    }

    @Test
    void reconciledAndNoopOutcomesDoNotClaimTheOperatorChangedTheAd() {
        AuthorizedAction action = authorized();
        GRAPH.on("GET ad-9001", ad("ACTIVE"));
        GRAPH.on("POST ad-9001", Reply.error(503, 2, 0));
        executor.execute(scope, action.id());
        GRAPH.on("GET ad-9001", ad("PAUSED"));
        AdPauseExecutor.Outcome reconciled = executor.execute(scope, action.id());
        executor.verify(scope, reconciled.executionId(), false);

        ActionOutcomes.Outcome outcome = outcomes.record(scope, reconciled.executionId());
        assertThat(outcome.authorship()).isEqualTo(ActionOutcomes.Authorship.UNCERTAIN);
        assertThat(outcome.resultCode()).isEqualTo("EFFECT_FOUND_ON_RECONCILIATION");
    }

    @Test
    void anAlreadyPausedAdHasNoAuthorAndNoObservationPlan() {
        AuthorizedAction action = authorized();
        GRAPH.on("GET ad-9001", ad("PAUSED"));
        AdPauseExecutor.Outcome noop = executor.execute(scope, action.id());

        ActionOutcomes.Outcome outcome = outcomes.record(scope, noop.executionId());
        assertThat(outcome.authorship()).isEqualTo(ActionOutcomes.Authorship.NONE);
        assertThat(outcome.observations()).isEmpty();
    }

    @Test
    void theDecisionDetailAndActivityShowWhatFollowedTheApproval() throws Exception {
        AuthorizedAction action = authorized();
        AdPauseExecutor.Outcome verified = pausedAndVerified(action);
        outcomes.record(scope, verified.executionId());

        mvc.perform(get("/api/v1/stores/{id}/decisions/{p}", store, action.proposalId()).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.decision").value("APPROVED"))
                .andExpect(jsonPath("$.execution.executionId").value(verified.executionId().toString()))
                .andExpect(jsonPath("$.execution.status").value("VERIFIED"))
                .andExpect(jsonPath("$.execution.outcome.authorship").value("OPERATOR"))
                .andExpect(jsonPath("$.execution.outcome.observations[0].checkpoint").value("H24"))
                .andExpect(jsonPath("$.execution.outcome.observations[0].status").value("PENDING"))
                .andExpect(jsonPath("$.allowedActions").isEmpty());

        String list = mvc.perform(get("/api/v1/stores/{id}/activities?type=ACTION", store).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("ACTION"))
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> actions = com.jayway.jsonpath.JsonPath.read(list, "$.items[*].action");
        assertThat(actions).contains("ACTION_AUTHORIZED", "ACTION_EXECUTED", "ACTION_VERIFIED",
                "ACTION_OUTCOME_RECORDED");
        java.util.List<String> executed = com.jayway.jsonpath.JsonPath.read(list,
                "$.items[?(@.action == 'ACTION_EXECUTED')].id");
        mvc.perform(get("/api/v1/stores/{id}/activities/{a}", store, executed.getFirst()).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activity.action").value("ACTION_EXECUTED"))
                .andExpect(jsonPath("$.executionId").value(verified.executionId().toString()))
                .andExpect(jsonPath("$.proposalId").value(action.proposalId().toString()));
        // Another store's person cannot open it.
        mvc.perform(get("/api/v1/stores/{id}/activities/{a}", otherStore, UUID.randomUUID()).with(user("outsider")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnconfirmedPauseIsShownAsUnknownNotAsDone() throws Exception {
        AuthorizedAction action = authorized();
        GRAPH.on("GET ad-9001", ad("ACTIVE"));
        GRAPH.on("POST ad-9001", Reply.error(503, 2, 0));
        executor.execute(scope, action.id());

        mvc.perform(get("/api/v1/stores/{id}/decisions/{p}", store, action.proposalId()).with(user("owner")))
                .andExpect(jsonPath("$.execution.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.execution.code").value("META_UNCERTAIN"))
                .andExpect(jsonPath("$.execution.outcome").doesNotExist());
    }

    long openSlots() {
        return jdbc.sql("SELECT count(*) FROM action_limit_slot WHERE store_id = ? AND released_at IS NULL")
                .param(store).query(Long.class).single();
    }
}
