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
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * B-22 scenario from the plan (section F, ADR-0012): 18 units in stock, 49 sold in 7 days, a dedicated verified ad
 * ad-9001 and a catalog ad ad-9002. The episode ends with RECOMMEND_ACTION to pause ad-9001, REQUIRE_APPROVAL
 * (L1) and AWAITING_DECISION, and nothing is changed in the ad.
 */
@SpringBootTest(properties = "spring.temporal.test-server.enabled=true")
@ActiveProfiles("worker")
@Import(TestcontainersConfiguration.class)
class InventoryGuardianWorkflowTest {

    static final UUID ORG = UUID.fromString("a8000000-0000-4000-8000-000000000001");
    static final UUID STORE = UUID.fromString("a8100000-0000-4000-8000-00000000000a");

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
    JdbcClient jdbc;
    @Autowired
    Clock clock;

    TrustedStoreScope scope;
    UUID serum;
    UUID dedicatedAd;
    UUID catalogAd;

    @BeforeEach
    void scenario() {
        when(health.status(any())).thenReturn(IntegrationHealth.Status.HEALTHY);
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Guardian org')").param(ORG).update();
        jdbc.sql("INSERT INTO store (id, organization_id, name, currency, timezone) VALUES (?, ?, 'S', 'PLN', 'UTC')")
                .params(STORE, ORG).update();
        scope = storeAccess.systemScope(EventDispatch.SYNC_SERVICE, ORG, STORE).orElseThrow();
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
    void scenarioRecommendsPausingTheDedicatedAdAndWaitsForADecisionWithoutChangingIt() throws Exception {
        Opportunity episode = opportunities.open(scope, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, serum).opportunity();
        InventoryGuardianWorkflow workflow = start(episode);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(workflow.status().opportunityStatus()).isEqualTo("AWAITING_DECISION"));
        GuardianStatus status = workflow.status();
        assertThat(status.disposition()).isEqualTo("RECOMMEND_ACTION");
        assertThat(status.policyOutcome()).isEqualTo("REQUIRE_APPROVAL");
        assertThat(opportunities.find(scope, episode.id()).orElseThrow().status())
                .isEqualTo(OpportunityStatus.AWAITING_DECISION);

        StoredProposal proposal = proposals.ofOpportunity(scope, episode.id()).getFirst();
        assertThat(proposal.status()).isEqualTo(ProposalStatus.PROPOSED);
        assertThat(proposal.proposal().recommendation().disposition()).isEqualTo(DecisionDisposition.RECOMMEND_ACTION);
        assertThat(proposal.proposal().recommendation().action()).isEqualTo(new PauseAd(dedicatedAd));
        assertThat(proposal.proposal().recommendation().reasonCodes()).contains("MULTI_PRODUCT_AD_EXCLUDED");
        assertThat(proposal.proposal().recommendation().summary()).contains("ad-9001", "ad-9002");
        assertThat(proposal.proposal().expiresAt())
                .isEqualTo(proposal.proposal().createdAt().plus(settings.proposalTtl()));

        PolicyEvaluation evaluation = policies.latest(scope, proposal.proposal().proposalId()).orElseThrow();
        assertThat(evaluation.policyKey() + "@" + evaluation.policyVersion()).isEqualTo("inventory-protection@1.0.0");
        assertThat(evaluation.mode()).isEqualTo(InventoryProtectionPolicy.Mode.EVALUATION_ONLY);
        assertThat(evaluation.outcome()).isEqualTo(PolicyOutcome.REQUIRE_APPROVAL);
        assertThat(evaluation.checks()).allMatch(check -> check.result() == PolicyCheck.Result.PASSED);

        // Shadow mode: no authorization exists and the ad was not touched.
        assertThat(ads.ad(scope, dedicatedAd).orElseThrow().ownStatus()).isEqualTo(AdStatus.ACTIVE);
        assertThat(ads.ad(scope, catalogAd).orElseThrow().ownStatus()).isEqualTo(AdStatus.ACTIVE);

        // A small change of cover is not a new analysis; a significant one is a new revision.
        workflow.riskUpdated(new RiskSignal("AT_RISK", "2.4000"));
        workflow.riskUpdated(new RiskSignal("AT_RISK", "1.2000"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(workflow.status().analyses()).isEqualTo(2);
            assertThat(workflow.status().opportunityStatus()).isEqualTo("AWAITING_DECISION");
        });
        assertThat(proposals.ofOpportunity(scope, episode.id())).extracting(StoredProposal::status)
                .containsExactly(ProposalStatus.SUPERSEDED, ProposalStatus.PROPOSED);

        // The risk is gone: the episode is resolved and its proposal no longer waits.
        workflow.riskResolved(new RiskSignal("NOT_AT_RISK", "5.0000"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(workflow.status().finished()).isTrue());
        assertThat(opportunities.find(scope, episode.id()).orElseThrow().status())
                .isEqualTo(OpportunityStatus.RESOLVED);
        assertThat(proposals.ofOpportunity(scope, episode.id())).extracting(StoredProposal::status)
                .containsExactly(ProposalStatus.SUPERSEDED, ProposalStatus.SUPERSEDED);

        String id = InventoryGuardianWorkflow.workflowId(STORE, serum.toString(), episode.id().toString());
        WorkflowExecutionHistory history = client.fetchHistory(id);
        WorkflowReplayer.replayWorkflowExecution(history, InventoryGuardianWorkflowImpl.class);
        // ./gradlew test -PrecordTemporalFixtures refreshes the fixture after an intentional, versioned change.
        if (Boolean.getBoolean("recordTemporalFixtures")) {
            Files.writeString(Path.of("src/test/resources/temporal/inventory-guardian-v1.json"),
                    history.toJson(true));
        }
    }

    @Test
    void staleIntegrationDefersAndTheEpisodeWaitsForData() {
        when(health.status(any())).thenReturn(IntegrationHealth.Status.STALE);
        Opportunity episode = opportunities.open(scope, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, serum).opportunity();
        InventoryGuardianWorkflow workflow = start(episode);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(workflow.status().opportunityStatus()).isEqualTo("WAITING_DATA"));
        assertThat(workflow.status().policyOutcome()).isEqualTo("DEFER");
        StoredProposal proposal = proposals.ofOpportunity(scope, episode.id()).getFirst();
        assertThat(policies.latest(scope, proposal.proposal().proposalId()).orElseThrow().decisiveCode())
                .isEqualTo("INTEGRATION_STALE");
    }

    @Test
    void recordedGuardianHistoryStillReplays() throws Exception {
        WorkflowReplayer.replayWorkflowExecutionFromResource("temporal/inventory-guardian-v1.json",
                InventoryGuardianWorkflowImpl.class);
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
