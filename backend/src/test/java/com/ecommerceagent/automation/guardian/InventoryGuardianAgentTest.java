package com.ecommerceagent.automation.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.events.EventDispatch;
import com.ecommerceagent.automation.temporal.StoreContextPropagator;
import com.ecommerceagent.automation.temporal.WorkflowStoreContext;
import com.ecommerceagent.commerce.AdData;
import com.ecommerceagent.commerce.AdMappingScope;
import com.ecommerceagent.commerce.AdRepository;
import com.ecommerceagent.commerce.AdSource;
import com.ecommerceagent.commerce.AdStatus;
import com.ecommerceagent.commerce.Backorders;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.InventoryReading;
import com.ecommerceagent.commerce.InventoryRepository;
import com.ecommerceagent.commerce.MappingVerification;
import com.ecommerceagent.commerce.ObservationSource;
import com.ecommerceagent.commerce.OrderData;
import com.ecommerceagent.commerce.OrderLineData;
import com.ecommerceagent.commerce.OrderRepository;
import com.ecommerceagent.commerce.ProductData;
import com.ecommerceagent.commerce.ReservationSemantics;
import com.ecommerceagent.commerce.StockStatus;
import com.ecommerceagent.commerce.StockTracking;
import com.ecommerceagent.commerce.VariantData;
import com.ecommerceagent.decisions.DecisionDisposition;
import com.ecommerceagent.decisions.DecisionProposals;
import com.ecommerceagent.decisions.InventoryRiskDetector;
import com.ecommerceagent.decisions.Opportunities;
import com.ecommerceagent.decisions.Opportunity;
import com.ecommerceagent.decisions.OpportunityCategory;
import com.ecommerceagent.decisions.OpportunityStatus;
import com.ecommerceagent.decisions.PauseAd;
import com.ecommerceagent.decisions.ProposalStatus;
import com.ecommerceagent.decisions.StoredProposal;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.IntegrationHealth;
import com.ecommerceagent.decisions.ProposerKind;
import com.ecommerceagent.intelligence.agent.AgentRun;
import com.ecommerceagent.intelligence.agent.AgentRunStatus;
import com.ecommerceagent.intelligence.agent.AgentRunStep;
import com.ecommerceagent.intelligence.agent.AgentRuns;
import com.ecommerceagent.intelligence.agent.FakeModelGateway;
import com.ecommerceagent.intelligence.model.provider.ProviderGateways;
import com.ecommerceagent.intelligence.inventory.InventoryReadTools;
import com.ecommerceagent.intelligence.model.ModelFailure;
import com.ecommerceagent.intelligence.model.ModelMessage;
import com.ecommerceagent.intelligence.model.ModelRequest;
import com.ecommerceagent.intelligence.model.ToolCall;
import com.ecommerceagent.metrics.CanonicalJson;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * B-30: the same 03 §2 scenario with the agent switched on, on a scripted model (ADR-0016). The agent reads through
 * the tools, its JSON is validated and stored as an AGENT proposal with its run; a failing model or a proposal the
 * validator rejects falls back to the baseline, and every attempt is recorded (IG-24, IG-28).
 */
@SpringBootTest(properties = {"spring.temporal.test-server.enabled=true", "operator.agent.enabled=true"})
@ActiveProfiles("worker")
@Import({TestcontainersConfiguration.class, InventoryGuardianAgentTest.FakeModel.class})
class InventoryGuardianAgentTest {

    static final FakeModelGateway MODEL = new FakeModelGateway();

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModel {

        @Bean
        @Primary
        ProviderGateways fakeModelProvider() {
            return ProviderGateways.of(MODEL, "anthropic");
        }
    }

    static final UUID ORG = UUID.fromString("a9000000-0000-4000-8000-000000000001");
    static final UUID STORE = UUID.fromString("a9100000-0000-4000-8000-00000000000a");

    @MockitoBean
    StoreIntegrationHealth health;
    @Autowired
    WorkflowClient client;
    @Autowired
    GuardianSettings settings;
    @Autowired
    Opportunities opportunities;
    @Autowired
    DecisionProposals proposals;
    @Autowired
    PolicyEvaluations policies;
    @Autowired
    InventoryRiskDetector detector;
    @Autowired
    CatalogRepository catalog;
    @Autowired
    InventoryRepository inventory;
    @Autowired
    OrderRepository orders;
    @Autowired
    AdRepository ads;
    @Autowired
    StoreAccess storeAccess;
    @Autowired
    FixtureDb jdbc;
    @Autowired
    Clock clock;

    TrustedStoreScope scope;
    UUID serum;
    UUID dedicatedAd;
    UUID catalogAd;

    @Autowired
    AgentRuns agentRuns;

    @BeforeEach
    void scenario() {
        MODEL.reset();
        when(health.status(any())).thenReturn(IntegrationHealth.Status.HEALTHY);
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Guardian org')").param(ORG).update();
        jdbc.sql("INSERT INTO store (id, organization_id, name, currency, timezone) VALUES (?, ?, 'S', 'PLN', 'UTC')")
                .params(STORE, ORG).update();
        scope = storeAccess.systemScope(EventDispatch.SYNC_SERVICE, ORG, STORE).orElseThrow();
        // Every model call is governed (ADR-0027): verified prices and the required platform and store budgets.
        for (String model : List.of("claude-opus-5-5", "claude-sonnet-5-5")) {
            jdbc.sql("""
                            INSERT INTO ai_price (id, provider, model, region, price_version, currency, input_per_mtok,
                                                  output_per_mtok, effective_from, source, verified_at)
                            VALUES (gen_random_uuid(), 'anthropic', ?, 'global', ?, 'USD', 4.00, 20.00,
                                    now() - interval '1 hour', 'test fixture', now())
                            """).params(model, "test-" + UUID.randomUUID()).update();
        }
        jdbc.sql("""
                        INSERT INTO ai_budget_policy (id, level, organization_id, store_id, period_type, amount_limit,
                                                      currency, policy_version, reason, created_by)
                        VALUES (gen_random_uuid(), 'PLATFORM', NULL, NULL, 'MONTHLY', 1000, 'USD', 1, 'test', 'test'),
                               (gen_random_uuid(), 'STORE', ?, ?, 'MONTHLY', 50, 'USD', 1, 'test', 'test')
                        """).params(ORG, STORE).update();
        Instant now = clock.instant();

        catalog.upsertProduct(scope, CommerceSource.WOOCOMMERCE, new ProductData("812", "Serum", null, "variable",
                "publish", now));
        serum = catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, new VariantData("826", "812",
                "Serum 30 ml", "SERUM-30", "publish", StockTracking.VARIANT, Backorders.NO, now));
        catalog.upsertProduct(scope, CommerceSource.WOOCOMMERCE, new ProductData("14", "Kubek", "MUG", "simple",
                "publish", now));
        UUID mug = catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, new VariantData("14", "14", "Kubek",
                "MUG", "publish", StockTracking.VARIANT, Backorders.NO, now));
        Instant today = now.truncatedTo(ChronoUnit.DAYS);
        for (int day = 1; day <= 7; day++) {
            Instant placedAt = today.minus(Duration.ofDays(day)).plus(Duration.ofHours(10));
            orders.upsert(scope, CommerceSource.WOOCOMMERCE, new OrderData("o-" + day, "processing", "PLN",
                    new BigDecimal("700.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, placedAt, placedAt,
                    null, placedAt, List.of(new OrderLineData("l-" + day, "826", "812", "SERUM-30", 7,
                            new BigDecimal("100.00"), BigDecimal.ZERO))));
        }
        orders.recordCoverage(scope, CommerceSource.WOOCOMMERCE, today.minus(Duration.ofDays(30)), now);
        inventory.record(scope, serum, new InventoryReading(18, StockStatus.IN_STOCK,
                ReservationSemantics.ALREADY_ACCOUNTED_FOR, null, ObservationSource.WEBHOOK, now, now));

        dedicatedAd = ads.upsertAd(scope, new AdData("ad-9001", "act-01", "Serum – dedykowana", AdStatus.ACTIVE,
                "ACTIVE", "fp-creative-44", now, AdSource.FIXTURE));
        ads.saveMapping(scope, dedicatedAd, AdMappingScope.SINGLE_VARIANT, List.of(serum),
                MappingVerification.VERIFIED, now);
        catalogAd = ads.upsertAd(scope, new AdData("ad-9002", "act-01", "Katalog", AdStatus.ACTIVE, "ACTIVE",
                "fp-catalog-07", now, AdSource.FIXTURE));
        ads.saveMapping(scope, catalogAd, AdMappingScope.MULTI_PRODUCT, List.of(serum, mug),
                MappingVerification.UNVERIFIED, null);
        // The detector's confirming observation (B-19).
        assertThat(detector.assess(scope, serum, now).observation().daysOfCover()).isEqualByComparingTo("2.5714");
    }

    @Test
    void agentProposalIsValidatedStoredAndEvaluatedWithItsRun() throws Exception {
        MODEL.thenTools(call("t1", InventoryReadTools.FORECAST, "productVariantId", serum),
                        call("t2", InventoryReadTools.MAPPINGS, "productVariantId", serum))
                .thenTools(call("t3", InventoryReadTools.DELIVERY, "adId", dedicatedAd))
                .then(request -> FakeModelGateway.text(pauseFrom(request, dedicatedAd)));
        Opportunity episode = opportunities.open(scope, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, serum).opportunity();
        InventoryGuardianWorkflow workflow = start(episode);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(workflow.status().opportunityStatus()).isEqualTo("AWAITING_DECISION"));
        GuardianStatus status = workflow.status();
        assertThat(status.proposerKind()).isEqualTo("AGENT");
        assertThat(status.agentFallback()).isNull();
        assertThat(status.policyOutcome()).isEqualTo("REQUIRE_APPROVAL");

        StoredProposal proposal = proposals.ofOpportunity(scope, episode.id()).getFirst();
        assertThat(proposal.proposal().proposer().kind()).isEqualTo(ProposerKind.AGENT);
        assertThat(proposal.proposal().recommendation().action()).isEqualTo(new PauseAd(dedicatedAd));
        AgentRun run = agentRuns.find(scope, proposal.proposal().agentRunId()).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(run.contextSnapshotId()).isEqualTo(proposal.proposal().contextSnapshotId());
        assertThat(run.model()).isEqualTo("claude-opus-5-5");
        assertThat(run.skillRef()).isEqualTo("inventory-guardian@1.0.0");
        assertThat(run.estimatedCostUsd()).isPositive();
        // The run records the versioned run policy and the limits it applied (ADR-0027 section 5).
        assertThat(run.runPolicy()).isEqualTo("inventory-agent-run/2");
        assertThat(jdbc.sql("SELECT run_limits ->> 'maxEstimatedCostUsd' || ':' || (run_limits ->> 'maxModelCalls')"
                + " FROM agent_run WHERE id = ?").param(run.id()).query(String.class).single()).isEqualTo("0.1:5");
        // The run was admitted and gave its lease back when it ended (ADR-0027 section 6.2).
        assertThat(jdbc.sql("SELECT count(*) FROM ai_run_lease WHERE agent_run_id = ? AND released_at IS NOT NULL")
                .param(run.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(run.situation()).startsWith("inventory/1:");
        // Each of the three model calls was reserved, sent once and settled with the provider's usage.
        assertThat(jdbc.sql("""
                        SELECT r.state || ':' || e.outcome || ':' || e.input_tokens FROM ai_budget_reservation r
                        JOIN ai_usage_event e ON e.reservation_id = r.id
                        WHERE r.agent_run_id = ? ORDER BY r.created_at
                        """).param(run.id()).query(String.class).list())
                .containsExactly("SETTLED:SETTLED:500", "SETTLED:SETTLED:500", "SETTLED:SETTLED:500");
        List<AgentRunStep> steps = agentRuns.steps(scope, run.id());
        assertThat(steps).extracting(AgentRunStep::kind).containsExactly(AgentRunStep.Kind.MODEL_CALL,
                AgentRunStep.Kind.TOOL_CALL, AgentRunStep.Kind.TOOL_CALL, AgentRunStep.Kind.MODEL_CALL,
                AgentRunStep.Kind.TOOL_CALL, AgentRunStep.Kind.MODEL_CALL);
        // Shadow mode: nothing changed in the ad.
        assertThat(ads.ad(scope, dedicatedAd).orElseThrow().ownStatus()).isEqualTo(AdStatus.ACTIVE);

        // IG-24: the stored steps hold the model's answer and every tool result, enough to replay the run.
        assertThat(CanonicalJson.read(steps.getLast().payload().get("text").asString())).isEqualTo(run.output());

        String id = InventoryGuardianWorkflow.workflowId(STORE, serum.toString(), episode.id().toString());
        WorkflowExecutionHistory history = client.fetchHistory(id);
        WorkflowReplayer.replayWorkflowExecution(history, InventoryGuardianWorkflowImpl.class);
        if (Boolean.getBoolean("recordTemporalFixtures")) {
            Files.writeString(Path.of("src/test/resources/temporal/inventory-guardian-v2.json"),
                    history.toJson(true));
        }
    }

    /**
     * ADR-0027 section 6.1 (criterion 22): a repeated delivery for the same episode never starts a second workflow,
     * so it never starts a second paid analysis. The webhook inbox already drops exact duplicates; this covers a
     * repeated start that gets past it.
     */
    @Test
    void aRepeatedStartOfTheSameEpisodeStartsNoSecondAgentRun() {
        MODEL.thenTools(call("t1", InventoryReadTools.FORECAST, "productVariantId", serum),
                        call("t2", InventoryReadTools.MAPPINGS, "productVariantId", serum))
                .thenTools(call("t3", InventoryReadTools.DELIVERY, "adId", dedicatedAd))
                .then(request -> FakeModelGateway.text(pauseFrom(request, dedicatedAd)));
        Opportunity episode = opportunities.open(scope, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, serum).opportunity();
        InventoryGuardianWorkflow workflow = start(episode);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(workflow.status().opportunityStatus()).isEqualTo("AWAITING_DECISION"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> start(episode))
                .isInstanceOf(io.temporal.client.WorkflowExecutionAlreadyStarted.class);

        assertThat(agentRuns.ofOpportunity(scope, episode.id())).hasSize(1);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM ai_budget_reservation r JOIN agent_run a ON a.id = r.agent_run_id
                        WHERE a.opportunity_id = ?
                        """).param(episode.id()).query(Long.class).single()).isEqualTo(3L);
    }

    @Test
    void ig28FailingModelsFallBackToTheBaselineAndEveryAttemptIsRecorded() {
        // A broken connection may have been charged; a rate-limit response is not billed.
        MODEL.thenFailure(ModelFailure.Type.UNAVAILABLE).thenFailure(ModelFailure.Type.RATE_LIMITED, true);
        Opportunity episode = opportunities.open(scope, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, serum).opportunity();
        InventoryGuardianWorkflow workflow = start(episode);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(workflow.status().opportunityStatus()).isEqualTo("AWAITING_DECISION"));
        assertThat(workflow.status().proposerKind()).isEqualTo("BASELINE");
        assertThat(workflow.status().agentFallback()).isEqualTo("AGENT_FAILED");
        StoredProposal proposal = proposals.ofOpportunity(scope, episode.id()).getFirst();
        assertThat(proposal.proposal().proposer().kind()).isEqualTo(ProposerKind.BASELINE);
        assertThat(agentRuns.ofOpportunity(scope, episode.id()))
                .extracting(AgentRun::attempt, AgentRun::model, AgentRun::status, AgentRun::stopReason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "claude-opus-5-5", AgentRunStatus.MODEL_FAILED,
                                "UNAVAILABLE"),
                        org.assertj.core.groups.Tuple.tuple(2, "claude-sonnet-5-5", AgentRunStatus.MODEL_FAILED,
                                "RATE_LIMITED"));
        assertThat(jdbc.sql("""
                        SELECT r.state || ':' || coalesce(r.state_reason, '-') || ':' || coalesce(r.settled_amount::text, '-')
                        FROM ai_budget_reservation r JOIN agent_run a ON a.id = r.agent_run_id
                        WHERE a.opportunity_id = ? ORDER BY a.attempt
                        """).param(episode.id()).query(String.class).list())
                .containsExactly("UNKNOWN:MODEL_FAILURE:UNAVAILABLE:-", "SETTLED:-:0.00000000");
    }

    @Test
    void ig07AgentProposalForAnAdOutsideTheContextIsRejectedAndTheBaselineDecides() {
        UUID foreign = UUID.randomUUID();
        MODEL.thenTools(call("t1", InventoryReadTools.FORECAST, "productVariantId", serum),
                        call("t2", InventoryReadTools.DELIVERY, "adId", dedicatedAd))
                .then(request -> FakeModelGateway.text(pauseFrom(request, foreign)));
        Opportunity episode = opportunities.open(scope, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, serum).opportunity();
        InventoryGuardianWorkflow workflow = start(episode);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(workflow.status().opportunityStatus()).isEqualTo("AWAITING_DECISION"));
        assertThat(workflow.status().proposerKind()).isEqualTo("BASELINE");
        assertThat(workflow.status().agentFallback()).startsWith("AGENT_PROPOSAL_REJECTED:");
        StoredProposal proposal = proposals.ofOpportunity(scope, episode.id()).getFirst();
        assertThat(proposal.proposal().recommendation().action()).isEqualTo(new PauseAd(dedicatedAd));
        assertThat(proposals.rejections(scope, episode.id())).hasSize(1);
        AgentRun run = agentRuns.ofOpportunity(scope, episode.id()).getFirst();
        assertThat(run.status()).isEqualTo(AgentRunStatus.OUTPUT_REJECTED);
        assertThat(run.stopReason()).isNotBlank();
        // Exactly one model attempt: a rejected answer is not retried on another model.
        assertThat(MODEL.requests()).hasSize(2);
    }

    static ToolCall call(String id, String tool, String field, UUID value) {
        return new ToolCall(id, tool, "{\"" + field + "\":\"" + value + "\"}");
    }

    /** A pause of {@code target}, citing the evidence the tools returned in this conversation. */
    static String pauseFrom(ModelRequest request, UUID target) {
        String stock = null;
        String state = null;
        for (ModelMessage message : request.messages()) {
            if (message instanceof ModelMessage.ToolResults results) {
                for (ModelMessage.ToolResult result : results.results()) {
                    JsonNode body = CanonicalJson.read(result.content());
                    if (body.has("stock")) {
                        stock = CanonicalJson.write(body.get("stock").get("evidence"));
                    }
                    if (body.has("stateEvidence")) {
                        state = CanonicalJson.write(body.get("stateEvidence"));
                    }
                }
            }
        }
        return """
                {"disposition":"RECOMMEND_ACTION",
                 "action":{"type":"PAUSE_AD","targetType":"META_AD","targetId":"%s",
                           "parameters":{"desiredStatus":"PAUSED"}},
                 "monitorPlan":null,"evidence":[%s,%s],"missingRequirements":[],
                 "reasonCodes":["COVER_BELOW_THRESHOLD","DEDICATED_AD_ACTIVE","MULTI_PRODUCT_AD_EXCLUDED"],
                 "summary":"Stock covers 2.6 days; pause the dedicated ad ad-9001, keep the catalog ad ad-9002.",
                 "uncertainties":["No confirmed delivery."]}
                """.formatted(target, stock, state);
    }

    private InventoryGuardianWorkflow start(Opportunity episode) {
        String id = InventoryGuardianWorkflow.workflowId(STORE, serum.toString(), episode.id().toString());
        InventoryGuardianWorkflow workflow = client.newWorkflowStub(InventoryGuardianWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TaskQueues.MAIN).setWorkflowId(id).build());
        StoreContextPropagator.callWith(WorkflowStoreContext.of(scope, "guardian-test"),
                () -> WorkflowClient.start(workflow::run, settings.input(episode.id().toString(), serum.toString())));
        return client.newWorkflowStub(InventoryGuardianWorkflow.class, id);
    }
}
