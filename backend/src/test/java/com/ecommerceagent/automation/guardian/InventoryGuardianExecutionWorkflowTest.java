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
import org.junit.jupiter.api.AfterAll;
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

/**
 * The approval branch of the guardian workflow (ADR-0020 point 8) against the Graph API stub: an approval signal
 * (and its duplicate) leads to one authorization, one write and a verification read; a rejection closes the episode.
 */
@SpringBootTest(properties = {"spring.temporal.test-server.enabled=true",
        "operator.policies.inventory-protection.mode=EXECUTE_ON_APPROVAL",
        "operator.integration.meta.poller-enabled=false"})
@ActiveProfiles({"worker", "test"})
@Import(TestcontainersConfiguration.class)
class InventoryGuardianExecutionWorkflowTest extends ExecutionWorkflowScenario {

    @Test
    void anApprovalIsAuthorizedExecutedOnceAndVerified() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean paused = new java.util.concurrent.atomic.AtomicBoolean();
        GRAPH.on("GET ad-9001", exchange -> ad(paused.get() ? "PAUSED" : "ACTIVE"));
        GRAPH.on("POST ad-9001", exchange -> {
            paused.set(true);
            return Reply.ok("{\"success\":true}");
        });
        Opportunity episode = opportunities.open(scope, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, serum).opportunity();
        InventoryGuardianWorkflow workflow = start(episode);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(workflow.status().awaitingApproval())
                .isTrue());
        assertThat(GRAPH.requests).isEmpty();

        UUID proposalId = proposals.ofOpportunity(scope, episode.id()).getFirst().proposal().proposalId();
        UUID approvalId = approvals.approve(owner, proposalId, 1, "wf-" + proposalId).approval().id();
        // A duplicate signal is handled once.
        workflow.approvalDecided(approvalId.toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(workflow.status().finished()).isTrue());
        GuardianStatus status = workflow.status();
        assertThat(status.executionStatus()).isEqualTo("VERIFIED");
        assertThat(opportunities.find(scope, episode.id()).orElseThrow().status()).isEqualTo(OpportunityStatus.CLOSED);
        assertThat(GRAPH.requests.stream().filter(r -> r.method().equals("POST")).count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM action_execution WHERE store_id = ?").param(STORE)
                .query(String.class).list()).containsExactly("VERIFIED");
        assertThat(jdbc.sql("SELECT authorship FROM action_outcome WHERE store_id = ?").param(STORE)
                .query(String.class).list()).containsExactly("OPERATOR");
        // The approvers were told twice: a decision is needed, then the confirmed pause (ADR-0021).
        assertThat(jdbc.sql("SELECT kind FROM operator_notification WHERE store_id = ? ORDER BY created_at")
                .param(STORE).query(String.class).list()).containsExactly("DECISION_NEEDED", "ACTION_VERIFIED");

        String id = InventoryGuardianWorkflow.workflowId(STORE, serum.toString(), episode.id().toString());
        WorkflowReplayer.replayWorkflowExecution(client.fetchHistory(id), InventoryGuardianWorkflowImpl.class);
    }

    @Test
    void aRejectionClosesTheEpisodeWithoutAnyMetaCall() {
        Opportunity episode = opportunities.open(scope, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, serum).opportunity();
        InventoryGuardianWorkflow workflow = start(episode);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(workflow.status().awaitingApproval())
                .isTrue());

        UUID proposalId = proposals.ofOpportunity(scope, episode.id()).getFirst().proposal().proposalId();
        approvals.reject(owner, proposalId, 1, "Campaign is planned", "wf-r-" + proposalId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(workflow.status().finished()).isTrue());
        assertThat(opportunities.find(scope, episode.id()).orElseThrow().status()).isEqualTo(OpportunityStatus.CLOSED);
        assertThat(GRAPH.requests).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM authorized_action WHERE store_id = ?").param(STORE)
                .query(Long.class).single()).isZero();
    }
}
