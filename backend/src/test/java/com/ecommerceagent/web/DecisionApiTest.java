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

/**
 * Decision and activity read API (B-23, ADR-0014) on the section F scenario: 18 units, 49 sold in 7 days, a
 * dedicated verified ad ad-9001 and a catalog ad ad-9002. The episode is driven through the same services the
 * guardian workflow uses. Store isolation checks are part of the merge-blocking safety tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class DecisionApiTest extends DecisionScenario {

    @Test
    void inboxListsTheEpisodeToApproveWithCountersAndMeta() throws Exception {
        UUID proposalId = awaitingDecision();

        mvc.perform(get("/api/v1/stores/{id}/decisions", store).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].proposalId").value(proposalId.toString()))
                .andExpect(jsonPath("$.items[0].group").value("TO_APPROVE"))
                .andExpect(jsonPath("$.items[0].status").value("PROPOSED"))
                .andExpect(jsonPath("$.items[0].disposition").value("RECOMMEND_ACTION"))
                .andExpect(jsonPath("$.items[0].subject.sku").value("SERUM-30"))
                .andExpect(jsonPath("$.items[0].facts.daysOfCover").value(2.5714))
                .andExpect(jsonPath("$.items[0].action.targetExternalId").value("ad-9001"))
                .andExpect(jsonPath("$.items[0].policyOutcome").value("REQUIRE_APPROVAL"))
                .andExpect(jsonPath("$.counts.toApprove").value(1))
                .andExpect(jsonPath("$.counts.monitoring").value(0))
                .andExpect(jsonPath("$.counts.completed").value(0))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.meta.timezone").value("UTC"))
                .andExpect(jsonPath("$.meta.completeness").value("COMPLETE"))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());

        mvc.perform(get("/api/v1/stores/{id}/decisions?group=COMPLETED", store).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.counts.toApprove").value(1));
    }

    @Test
    void aReanalysisReplacesTheRowAndKeepsTheOldRevisionInTheDetail() throws Exception {
        UUID first = awaitingDecision();
        UUID second = analyse(episode, OpportunityStatus.AWAITING_DECISION);

        mvc.perform(get("/api/v1/stores/{id}/decisions", store).with(user("owner")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].proposalId").value(second.toString()))
                .andExpect(jsonPath("$.items[0].revision").value(2));
        mvc.perform(get("/api/v1/stores/{id}/decisions/{p}", store, first).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.blockedReason").doesNotExist())
                .andExpect(jsonPath("$.revisions.length()").value(2))
                .andExpect(jsonPath("$.revisions[1].proposalId").value(second.toString()));
    }

    @Test
    void cursorPagesWithoutRepeatsAndRejectsForeignOrMalformedCursors() throws Exception {
        // Three episodes of three variants.
        awaitingDecision();
        Instant now = clock.instant();
        for (String sku : List.of("SERUM-50", "SERUM-100")) {
            UUID variant = catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, new VariantData(sku, "812",
                    sku, sku, "publish", StockTracking.VARIANT, Backorders.NO, now));
            inventory.record(scope, variant, new InventoryReading(1, StockStatus.IN_STOCK,
                    ReservationSemantics.ALREADY_ACCOUNTED_FOR, null, ObservationSource.WEBHOOK, now, now));
            analyse(opportunities.open(scope, OpportunityCategory.INVENTORY_RISK, Opportunity.PRODUCT_VARIANT,
                    variant).opportunity(), OpportunityStatus.AWAITING_DECISION);
        }

        String page1 = mvc.perform(get("/api/v1/stores/{id}/decisions?limit=2", store).with(user("owner")))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.counts.toApprove").value(3))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");
        String page2 = mvc.perform(get("/api/v1/stores/{id}/decisions?limit=2&cursor={c}", store, cursor)
                        .with(user("owner")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(page1, "$.items[*].proposalId");
        List<String> rest = JsonPath.read(page2, "$.items[*].proposalId");
        assertThat(ids).doesNotContainAnyElementsOf(rest);

        mvc.perform(get("/api/v1/stores/{id}/decisions?group=COMPLETED&cursor={c}", store, cursor)
                        .with(user("owner")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        mvc.perform(get("/api/v1/stores/{id}/decisions?cursor=not-base64!", store).with(user("owner")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        mvc.perform(get("/api/v1/stores/{id}/decisions?limit=0", store).with(user("owner")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
        mvc.perform(get("/api/v1/stores/{id}/decisions?group=ALL", store).with(user("owner")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GROUP"));
    }

    @Test
    void detailPresentsTheProposalPolicyAndShadowModeWithAnEtag() throws Exception {
        UUID proposalId = awaitingDecision();

        String etag = mvc.perform(get("/api/v1/stores/{id}/decisions/{p}", store, proposalId).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.group").value("TO_APPROVE"))
                .andExpect(jsonPath("$.opportunityStatus").value("AWAITING_DECISION"))
                .andExpect(jsonPath("$.businessSummary").value(containsString("ad-9001")))
                .andExpect(jsonPath("$.reasonCodes").value(hasItem("MULTI_PRODUCT_AD_EXCLUDED")))
                .andExpect(jsonPath("$.subject.productName").value("Serum"))
                .andExpect(jsonPath("$.facts.availableToSell").value(18))
                .andExpect(jsonPath("$.facts.thresholdDays").exists())
                .andExpect(jsonPath("$.action.type").value("PAUSE_AD"))
                .andExpect(jsonPath("$.action.targetId").value(dedicatedAd.toString()))
                .andExpect(jsonPath("$.action.targetName").value("Serum dedicated"))
                .andExpect(jsonPath("$.excludedAds[0].externalId").value("ad-9002"))
                .andExpect(jsonPath("$.excludedAds[0].reason").value("MULTI_PRODUCT_AD"))
                .andExpect(jsonPath("$.policyEvaluation.outcome").value("REQUIRE_APPROVAL"))
                .andExpect(jsonPath("$.policyEvaluation.mode").value("EVALUATION_ONLY"))
                .andExpect(jsonPath("$.policyEvaluation.checks.length()").value(greaterThan(0)))
                .andExpect(jsonPath("$.proposer.kind").value("BASELINE"))
                .andExpect(jsonPath("$.allowedActions.length()").value(0))
                .andExpect(jsonPath("$.blockedReason").value("SHADOW_MODE"))
                .andExpect(jsonPath("$.execution").doesNotExist())
                .andReturn().getResponse().getHeader("ETag");
        assertThat(etag).startsWith("\"").endsWith("\"");

        mvc.perform(get("/api/v1/stores/{id}/decisions/{p}", store, proposalId).with(user("owner"))
                        .header("If-None-Match", etag))
                .andExpect(status().isNotModified());

        // A status change is a new version.
        proposals.close(scope, proposalId, ProposalStatus.EXPIRED);
        mvc.perform(get("/api/v1/stores/{id}/decisions/{p}", store, proposalId).with(user("owner"))
                        .header("If-None-Match", etag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.blockedReason").value("PROPOSAL_EXPIRED"));
    }

    @Test
    void evidenceReturnsTheSnapshotAndTheCitedEvidence() throws Exception {
        UUID proposalId = awaitingDecision();

        mvc.perform(get("/api/v1/stores/{id}/decisions/{p}/evidence", store, proposalId).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.contentSchema").value("inventory-context/1"))
                .andExpect(jsonPath("$.snapshot.contentHash").value(startsWith("sha256:")))
                .andExpect(jsonPath("$.items[?(@.type == 'DAYS_OF_COVER')].citedByProposal")
                        .value(hasItem(true)))
                .andExpect(jsonPath("$.items[?(@.type == 'AD_MAPPING')]").isNotEmpty())
                .andExpect(jsonPath("$.items[0].contentHash").value(startsWith("sha256:")))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.meta.completeness").value("COMPLETE"));
    }

    @Test
    void activityShowsTheEpisodeProposalAndPolicyStepsWithoutPersonalData() throws Exception {
        awaitingDecision();

        String body = mvc.perform(get("/api/v1/stores/{id}/activities", store).with(user("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("OPPORTUNITY_STATUS_CHANGED"))
                .andExpect(jsonPath("$.items[0].details.to").value("AWAITING_DECISION"))
                .andExpect(jsonPath("$.items[0].actor.type").value("SYSTEM"))
                .andExpect(jsonPath("$.items[?(@.action == 'POLICY_EVALUATED')].details.outcome")
                        .value(hasItem("REQUIRE_APPROVAL")))
                .andExpect(jsonPath("$.items[?(@.action == 'PROPOSAL_CREATED')].type")
                        .value(hasItem("RECOMMENDATION")))
                .andExpect(jsonPath("$.items[?(@.action == 'OPPORTUNITY_OPENED')].type")
                        .value(hasItem("DETECTION")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("@shop.test");

        mvc.perform(get("/api/v1/stores/{id}/activities?type=POLICY", store).with(user("owner")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("POLICY"));
        mvc.perform(get("/api/v1/stores/{id}/activities?limit=2", store).with(user("owner")))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(get("/api/v1/stores/{id}/activities?type=ACCESS", store).with(user("owner")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TYPE"));
    }

    @Test
    void aUserOfAnotherStoreSeesNoProposalEvidenceOrActivity() throws Exception {
        UUID proposalId = awaitingDecision();

        // The outsider's own store is empty.
        mvc.perform(get("/api/v1/stores/{id}/decisions", otherStore).with(user("outsider")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.counts.toApprove").value(0));
        mvc.perform(get("/api/v1/stores/{id}/activities", otherStore).with(user("outsider")))
                .andExpect(jsonPath("$.items.length()").value(0));
        // The proposal id of another store does not resolve, even through the outsider's own store.
        for (String path : List.of("/api/v1/stores/{id}/decisions/{p}", "/api/v1/stores/{id}/decisions/{p}/evidence")) {
            mvc.perform(get(path, otherStore, proposalId).with(user("outsider")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            mvc.perform(get(path, store, proposalId).with(user("outsider")))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/stores/{id}/decisions", store).with(user("outsider")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stores/{id}/activities", store).with(user("outsider")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stores/{id}/decisions/not-a-uuid", store).with(user("owner")))
                .andExpect(status().isNotFound());
    }
}
