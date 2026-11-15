package com.ecommerceagent.automation.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
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
import com.ecommerceagent.integration.MetaConnections;
import com.ecommerceagent.integration.meta.GraphApiStub;
import com.ecommerceagent.integration.meta.GraphApiStub.Reply;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The section F scenario with a connected Meta account, execution switched on and the Graph API stub (ADR-0020). */
abstract class ExecutionWorkflowScenario {


    static final UUID ORG = UUID.fromString("a9000000-0000-4000-8000-000000000001");
    static final UUID OWNER = UUID.fromString("a9300000-0000-4000-8000-0000000000a1");
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

    // The stub lives as long as the test JVM: several test classes share it through the cached context.

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
    JdbcClient jdbc;
    @Autowired
    Clock clock;

    @Autowired
    DecisionApprovals approvals;
    @Autowired
    MetaConnections connections;

    TrustedStoreScope scope;
    TrustedStoreScope owner;
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
        jdbc.sql("INSERT INTO app_user (id, issuer, subject, email) VALUES (?, 'https://issuer.test', 'owner', "
                + "'o@shop.test')").param(OWNER).update();
        jdbc.sql("INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES (?, ?, ?, 'OWNER')")
                .params(ORG, STORE, OWNER).update();
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
                "ACTIVE", "meta-creative:44", now, AdSource.FIXTURE));
        ads.saveMapping(scope, dedicatedAd, AdMappingScope.SINGLE_VARIANT, List.of(serum),
                MappingVerification.VERIFIED, now);
        catalogAd = ads.upsertAd(scope, new AdData("ad-9002", "act-01", "Katalog", AdStatus.ACTIVE, "ACTIVE",
                "fp-catalog-07", now, AdSource.FIXTURE));
        ads.saveMapping(scope, catalogAd, AdMappingScope.MULTI_PRODUCT, List.of(serum, mug),
                MappingVerification.UNVERIFIED, null);
        owner = storeAccess.scopeFor(OWNER, STORE).orElseThrow();
        UUID connection = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO meta_connection (id, organization_id, store_id, account_id, status, can_write, created_at,
                                             updated_at)
                VALUES (?, ?, ?, '1234567890', 'CONNECTED', true, now(), now())
                """).params(connection, ORG, STORE).update();
        connections.saveToken(owner, connection, GraphApiStub.TOKEN);
        jdbc.sql("UPDATE meta_connection SET status = 'CONNECTED', can_write = true WHERE id = ?")
                .param(connection).update();
        GRAPH.reset();
        // The detector's confirming observation (B-19).
        assertThat(detector.assess(scope, serum, now).observation().daysOfCover()).isEqualByComparingTo("2.5714");
    }

    static Reply ad(String status) {
        return Reply.ok("{\"id\":\"ad-9001\",\"status\":\"" + status + "\",\"effective_status\":\"" + status
                + "\",\"creative\":{\"id\":\"44\"}}");
    }

    InventoryGuardianWorkflow start(Opportunity episode) {
        String id = InventoryGuardianWorkflow.workflowId(STORE, serum.toString(), episode.id().toString());
        InventoryGuardianWorkflow workflow = client.newWorkflowStub(InventoryGuardianWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TaskQueues.MAIN).setWorkflowId(id).build());
        StoreContextPropagator.callWith(WorkflowStoreContext.of(scope, "guardian-test"),
                () -> WorkflowClient.start(workflow::run, settings.input(episode.id().toString(), serum.toString())));
        return client.newWorkflowStub(InventoryGuardianWorkflow.class, id);
    }
}
