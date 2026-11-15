package com.ecommerceagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.events.EventDispatch;
import com.ecommerceagent.automation.guardian.PolicyEvaluations;
import com.ecommerceagent.automation.guardian.StoreIntegrationHealth;
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
import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.decisions.DecisionProposals;
import com.ecommerceagent.decisions.InventoryRiskDetector;
import com.ecommerceagent.decisions.Opportunities;
import com.ecommerceagent.decisions.Opportunity;
import com.ecommerceagent.decisions.OpportunityCategory;
import com.ecommerceagent.decisions.OpportunityStatus;
import com.ecommerceagent.decisions.ProposalJson;
import com.ecommerceagent.decisions.ProposalStatus;
import com.ecommerceagent.decisions.ProposalSubmission;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.IntegrationHealth;
import com.ecommerceagent.intelligence.inventory.BaselineProposer;
import com.ecommerceagent.intelligence.inventory.InventoryContext;
import com.ecommerceagent.intelligence.inventory.InventoryContextBuilder;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The section F scenario of the decision API tests: one store, the serum variant, its two ads and an episode. */
abstract class DecisionScenario {


    static final String ISSUER = "https://issuer.test/realms/operator";
    static final UUID ORG = UUID.fromString("b8000000-0000-4000-8000-000000000001");
    static final UUID OWNER = UUID.fromString("b8300000-0000-4000-8000-0000000000a1");
    static final UUID OUTSIDER = UUID.fromString("b8300000-0000-4000-8000-0000000000b1");

    @MockitoBean
    StoreIntegrationHealth health;
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    StoreAccess storeAccess;
    @Autowired
    CatalogRepository catalog;
    @Autowired
    InventoryRepository inventory;
    @Autowired
    OrderRepository orders;
    @Autowired
    AdRepository ads;
    @Autowired
    Opportunities opportunities;
    @Autowired
    InventoryContextBuilder contexts;
    @Autowired
    BaselineProposer baseline;
    @Autowired
    DecisionProposals proposals;
    @Autowired
    PolicyEvaluations policies;
    @Autowired
    InventoryRiskDetector detector;
    @Autowired
    Clock clock;

    UUID store;
    UUID otherStore;
    TrustedStoreScope scope;
    UUID serum;
    UUID dedicatedAd;
    UUID catalogAd;
    Opportunity episode;

    @BeforeEach
    void scenario() {
        when(health.status(any())).thenReturn(IntegrationHealth.Status.HEALTHY);
        // Fresh store ids per test: audit_event is append-only and keeps events of earlier runs.
        store = UUID.randomUUID();
        otherStore = UUID.randomUUID();
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Decision org')").param(ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone) VALUES
                  (?, ?, 'Store A', 'PLN', 'UTC'), (?, ?, 'Store B', 'PLN', 'Europe/Warsaw')
                """).params(store, ORG, otherStore, ORG).update();
        jdbc.sql("INSERT INTO app_user (id, issuer, subject, email, display_name) VALUES (?, ?, 'owner', 'o@shop.test', "
                + "'Olga Owner'), (?, ?, 'outsider', 'x@shop.test', null)")
                .params(OWNER, ISSUER, OUTSIDER, ISSUER).update();
        jdbc.sql("""
                INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES
                  (?, ?, ?, 'OWNER'), (?, ?, ?, 'OWNER')
                """).params(ORG, store, OWNER, ORG, otherStore, OUTSIDER).update();
        // ADR-0025: execution needs the store's monitoring start; tests of the gate remove it.
        jdbc.sql("""
                INSERT INTO onboarding_progress (store_id, organization_id, current_step, updated_at,
                                                 monitoring_started_at)
                VALUES (?, ?, 'START_MONITORING', now(), now())
                """).params(store, ORG).update();
        scope = storeAccess.systemScope(EventDispatch.SYNC_SERVICE, ORG, store).orElseThrow();

        Instant now = clock.instant();
        catalog.upsertProduct(scope, CommerceSource.WOOCOMMERCE, new ProductData("812", "Serum", null, "variable",
                "publish", now));
        serum = catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, new VariantData("826", "812",
                "Serum 30 ml", "SERUM-30", "publish", StockTracking.VARIANT, Backorders.NO, now));
        catalog.upsertProduct(scope, CommerceSource.WOOCOMMERCE, new ProductData("14", "Mug", "MUG", "simple",
                "publish", now));
        UUID mug = catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, new VariantData("14", "14", "Mug",
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
        dedicatedAd = ads.upsertAd(scope, new AdData("ad-9001", "act-01", "Serum dedicated", AdStatus.ACTIVE,
                "ACTIVE", "fp-creative-44", now, AdSource.FIXTURE));
        ads.saveMapping(scope, dedicatedAd, AdMappingScope.SINGLE_VARIANT, List.of(serum),
                MappingVerification.VERIFIED, now);
        catalogAd = ads.upsertAd(scope, new AdData("ad-9002", "act-01", "Catalog", AdStatus.ACTIVE, "ACTIVE",
                "fp-catalog-07", now, AdSource.FIXTURE));
        ads.saveMapping(scope, catalogAd, AdMappingScope.MULTI_PRODUCT, List.of(serum, mug),
                MappingVerification.UNVERIFIED, null);
        // The detector's confirming observation (B-19), which the policy requires.
        detector.assess(scope, serum, now);
    }

    /** One analysis as the guardian runs it: context, baseline, validated proposal, policy, then the next status. */
    UUID analyse(Opportunity episode, OpportunityStatus next) {
        opportunities.transition(scope, episode.id(), OpportunityStatus.ANALYZING, null);
        ContextSnapshot snapshot = contexts.build(scope, opportunities.find(scope, episode.id()).orElseThrow());
        ProposalSubmission submission = proposals.submit(scope, episode.id(), snapshot.id(), BaselineProposer.PROPOSER,
                null, ProposalJson.write(baseline.propose(InventoryContext.of(snapshot))), Duration.ofHours(24));
        assertThat(submission.accepted()).isTrue();
        UUID proposalId = submission.proposal().proposal().proposalId();
        if (submission.proposal().proposal().recommendation().action() != null) {
            policies.evaluate(scope, proposalId);
        }
        opportunities.transition(scope, episode.id(), next, null);
        return proposalId;
    }

    UUID awaitingDecision() {
        episode = opportunities.open(scope, OpportunityCategory.INVENTORY_RISK, Opportunity.PRODUCT_VARIANT, serum)
                .opportunity();
        return analyse(episode, OpportunityStatus.AWAITING_DECISION);
    }

    static RequestPostProcessor user(String subject) {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("email", subject + "@shop.test"));
    }

}
