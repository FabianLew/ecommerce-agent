package com.ecommerceagent.automation.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.execution.ExecutionLedger;
import com.ecommerceagent.automation.execution.ExecutionStatus;
import com.ecommerceagent.automation.notifications.MailProvider;
import com.ecommerceagent.decisions.Opportunity;
import com.ecommerceagent.decisions.OpportunityCategory;
import com.ecommerceagent.decisions.OpportunityStatus;
import com.ecommerceagent.integration.meta.GraphApiStub.Reply;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.WorkerCrash;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Fault injection (B-43, document 03 §12): a worker crash after Meta accepted the write (IG-19), a worker restart
 * and replay while the episode waits for approval (IG-24), and a broken notification channel (IG-23). In each case
 * the pause is sent once and its confirmed state is kept.
 */
@SpringBootTest(properties = {"spring.temporal.test-server.enabled=true",
        "operator.policies.inventory-protection.mode=EXECUTE_ON_APPROVAL",
        "operator.integration.meta.poller-enabled=false"})
@ActiveProfiles({"worker", "test"})
@Import(TestcontainersConfiguration.class)
class GuardianFaultInjectionTest extends ExecutionWorkflowScenario {

    @MockitoSpyBean
    ExecutionLedger ledger;
    @MockitoBean
    MailProvider mail;
    @Autowired
    WorkerFactory workers;

    final AtomicBoolean paused = new AtomicBoolean();

    void metaPausesOnWrite() {
        GRAPH.on("GET ad-9001", exchange -> ad(paused.get() ? "PAUSED" : "ACTIVE"));
        GRAPH.on("POST ad-9001", exchange -> {
            paused.set(true);
            return Reply.ok("{\"success\":true}");
        });
    }

    long writes() {
        return GRAPH.requests.stream().filter(r -> r.method().equals("POST")).count();
    }

    InventoryGuardianWorkflow waitingForApproval(Opportunity episode) {
        InventoryGuardianWorkflow workflow = start(episode);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(workflow.status().awaitingApproval())
                .isTrue());
        return workflow;
    }

    UUID approve(Opportunity episode) {
        UUID proposalId = proposals.ofOpportunity(scope, episode.id()).getFirst().proposal().proposalId();
        return approvals.approve(owner, proposalId, 1, "fi-" + proposalId).approval().id();
    }

    @Test
    void ig19aCrashAfterTheAcceptedWriteIsReconciledWithoutASecondWrite() {
        metaPausesOnWrite();
        // The worker dies after Meta accepted the pause, before the ledger records it: the activity fails once.
        doThrow(new IllegalStateException("worker crashed after the write")).doCallRealMethod().when(ledger)
                .move(any(), any(), eq(ExecutionStatus.APPLIED), any(), any(), anyBoolean());
        Opportunity episode = opportunities.open(scope, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, serum).opportunity();
        InventoryGuardianWorkflow workflow = waitingForApproval(episode);

        approve(episode);

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(workflow.status().finished()).isTrue());
        assertThat(workflow.status().executionStatus()).isEqualTo("VERIFIED");
        assertThat(writes()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT result_code FROM action_execution WHERE store_id = ?").param(STORE)
                .query(String.class).single()).isEqualTo("EFFECT_FOUND_ON_RECONCILIATION");
        // We cannot prove the effect is ours: the outcome says so.
        assertThat(jdbc.sql("SELECT authorship FROM action_outcome WHERE store_id = ?").param(STORE)
                .query(String.class).single()).isEqualTo("UNCERTAIN");
    }

    @Test
    void ig24aWorkerRestartWhileWaitingKeepsTheStateAndNeverRepeatsTheAction() throws Exception {
        metaPausesOnWrite();
        Opportunity episode = opportunities.open(scope, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, serum).opportunity();
        InventoryGuardianWorkflow workflow = waitingForApproval(episode);
        String id = InventoryGuardianWorkflow.workflowId(STORE, serum.toString(), episode.id().toString());

        // The worker goes down while the episode waits; the approval arrives meanwhile.
        workers.suspendPolling();
        WorkerCrash.forgetAll(workers);
        UUID approvalId;
        try {
            WorkflowReplayer.replayWorkflowExecution(client.fetchHistory(id), InventoryGuardianWorkflowImpl.class);
            approvalId = approve(episode);
        } finally {
            workers.resumePolling();
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(workflow.status().finished()).isTrue());
        assertThat(workflow.status().executionStatus()).isEqualTo("VERIFIED");
        assertThat(writes()).isEqualTo(1);

        // Another restart and a late duplicate signal: the episode is complete, so the signal is refused and the
        // query rebuilds the state from history without running any activity again.
        WorkerCrash.forgetAll(workers);
        UUID late = approvalId;
        assertThatThrownBy(() -> workflow.approvalDecided(late.toString()))
                .isInstanceOf(io.temporal.client.WorkflowNotFoundException.class);
        assertThat(workflow.status().executionStatus()).isEqualTo("VERIFIED");
        WorkflowReplayer.replayWorkflowExecution(client.fetchHistory(id), InventoryGuardianWorkflowImpl.class);
        assertThat(writes()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM authorized_action WHERE store_id = ?").param(STORE)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM operator_notification WHERE store_id = ? AND kind = "
                + "'DECISION_NEEDED'").param(STORE).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void ig23aBrokenNotificationChannelKeepsTheConfirmedPause() {
        metaPausesOnWrite();
        doThrow(new IllegalStateException("mail provider down")).when(mail).send(any());
        Opportunity episode = opportunities.open(scope, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, serum).opportunity();
        InventoryGuardianWorkflow workflow = waitingForApproval(episode);

        approve(episode);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(workflow.status().finished()).isTrue());
        assertThat(workflow.status().executionStatus()).isEqualTo("VERIFIED");
        assertThat(opportunities.find(scope, episode.id()).orElseThrow().status()).isEqualTo(OpportunityStatus.CLOSED);
        assertThat(writes()).isEqualTo(1);
        // The notification exists; only its e-mail is uncertain (the provider may have taken it) and is reconciled,
        // never resent blindly (ADR-0021 point 2).
        assertThat(jdbc.sql("SELECT count(*) FROM operator_notification WHERE store_id = ? AND kind = "
                + "'ACTION_VERIFIED'").param(STORE).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT DISTINCT status FROM notification_delivery d JOIN operator_notification n "
                + "ON n.id = d.notification_id WHERE n.store_id = ? AND n.kind = 'ACTION_VERIFIED'").param(STORE)
                .query(String.class).list()).containsExactly("UNKNOWN");
    }
}
