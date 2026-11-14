package com.ecommerceagent.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import com.ecommerceagent.Correlation;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.diagnostics.ScopeCheckWorkflow;
import com.ecommerceagent.automation.diagnostics.ScopeCheckWorkflowImpl;
import com.ecommerceagent.automation.temporal.StoreContextPropagator;
import com.ecommerceagent.automation.temporal.StoreScopeActivityInterceptor;
import com.ecommerceagent.automation.temporal.WorkflowStoreContext;
import com.ecommerceagent.identity.StoreRole;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * B-08 runtime safety: store scope and correlation travel through Temporal, and activities only run
 * with a scope re-verified against memberships (ADR-0004). Runs in TestWorkflowEnvironment.
 */
@SpringBootTest(properties = "spring.temporal.test-server.enabled=true")
@ActiveProfiles("worker")
@Import(TestcontainersConfiguration.class)
class WorkflowStoreScopeTest {

    static final UUID ORG = UUID.fromString("40000000-0000-4000-8000-000000000001");
    static final UUID STORE = UUID.fromString("40000000-0000-4000-8000-00000000000a");
    static final UUID OTHER_STORE = UUID.fromString("40000000-0000-4000-8000-00000000000b");
    static final UUID MEMBER = UUID.fromString("40000000-0000-4000-8000-0000000000a1");

    @Autowired
    WorkflowDiagnostics diagnostics;

    @Autowired
    WorkflowClient client;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void tenant() {
        jdbc.sql("DELETE FROM store_membership WHERE organization_id = ?").param(ORG).update();
        jdbc.sql("DELETE FROM store WHERE organization_id = ?").param(ORG).update();
        jdbc.sql("DELETE FROM organization WHERE id = ?").param(ORG).update();
        jdbc.sql("DELETE FROM app_user WHERE id = ?").param(MEMBER).update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Temporal org')").param(ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone)
                VALUES (?, ?, 'S1', 'PLN', 'UTC'), (?, ?, 'S2', 'PLN', 'UTC')
                """).params(STORE, ORG, OTHER_STORE, ORG).update();
        jdbc.sql("INSERT INTO app_user (id, issuer, subject) VALUES (?, 'https://issuer.test', 'temporal-member')")
                .param(MEMBER).update();
        jdbc.sql("""
                INSERT INTO store_membership (organization_id, store_id, user_id, role)
                VALUES (?, ?, ?, 'DECISION_OPERATOR'), (?, ?, ?, 'ANALYST')
                """).params(ORG, STORE, MEMBER, ORG, STORE, MEMBER).update();
    }

    @AfterEach
    void clearMdc() {
        MDC.remove(Correlation.CORRELATION_ID);
    }

    @Test
    void verifiedScopeAndCorrelationReachTheActivity() {
        MDC.put(Correlation.CORRELATION_ID, "flow-temporal-test-1");

        ScopeCheckResult result = diagnostics.checkStoreScope(
                new TrustedStoreScope(ORG, STORE, MEMBER, Set.of(StoreRole.ANALYST)));

        assertThat(result.organizationId()).isEqualTo(ORG.toString());
        assertThat(result.storeId()).isEqualTo(STORE.toString());
        assertThat(result.userId()).isEqualTo(MEMBER.toString());
        // Roles come from the database at activity time, not from the caller's scope object.
        assertThat(result.roles()).containsExactly("ANALYST", "DECISION_OPERATOR");
        assertThat(result.correlationId()).isEqualTo("flow-temporal-test-1");
        assertThat(result.workflowId()).startsWith("scope-check-" + STORE);
    }

    @Test
    void forgedStoreClaimIsRejectedWithoutRetry() {
        // The member is not in OTHER_STORE: a header claim alone must not grant access.
        ApplicationFailure failure = failureOf(new WorkflowStoreContext(ORG, OTHER_STORE, MEMBER, "flow-forged"));

        assertThat(failure.getType()).isEqualTo(StoreScopeActivityInterceptor.SCOPE_REJECTED);
        assertThat(failure.isNonRetryable()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit_event
                        WHERE action = 'STORE_ACCESS_DENIED' AND resource_id = ? AND details->>'channel' = 'temporal-activity'
                        """).param(OTHER_STORE.toString()).query(Long.class).single()).isPositive();
    }

    @Test
    void claimWithMismatchedOrganizationIsRejected() {
        ApplicationFailure failure = failureOf(new WorkflowStoreContext(UUID.randomUUID(), STORE, MEMBER, null));

        assertThat(failure.getType()).isEqualTo(StoreScopeActivityInterceptor.SCOPE_REJECTED);
    }

    @Test
    void activityWithoutStoreClaimDoesNotRun() {
        ApplicationFailure failure = failureOf(null);

        assertThat(failure.getType()).isEqualTo(StoreScopeActivityInterceptor.SCOPE_MISSING);
        assertThat(failure.isNonRetryable()).isTrue();
    }

    @Test
    void scopeDoesNotLeakBetweenExecutionsOnReusedThreads() {
        // Regression: pooled workflow threads kept the previous execution's context when the next
        // execution had no header, so an unscoped workflow ran with someone else's store scope.
        for (int i = 0; i < 3; i++) {
            diagnostics.checkStoreScope(new TrustedStoreScope(ORG, STORE, MEMBER, Set.of(StoreRole.ANALYST)));
            assertThat(failureOf(null).getType()).isEqualTo(StoreScopeActivityInterceptor.SCOPE_MISSING);
        }
    }

    @Test
    void completedHistoryReplaysDeterministically() throws Exception {
        ScopeCheckResult result = diagnostics.checkStoreScope(
                new TrustedStoreScope(ORG, STORE, MEMBER, Set.of(StoreRole.ANALYST)));

        WorkflowExecutionHistory history = client.fetchHistory(result.workflowId());
        WorkflowReplayer.replayWorkflowExecution(history, ScopeCheckWorkflowImpl.class);

        // ./gradlew test -PrecordTemporalFixtures refreshes the fixture after an intentional, versioned change.
        if (Boolean.getBoolean("recordTemporalFixtures")) {
            java.nio.file.Files.writeString(java.nio.file.Path.of("src/test/resources/temporal/scope-check-v1.json"),
                    history.toJson(true));
        }
    }

    @Test
    void recordedHistoryFromFixtureStillReplays() throws Exception {
        // Guards against non-deterministic changes to ScopeCheckWorkflowImpl (recorded with the v1 code).
        WorkflowReplayer.replayWorkflowExecutionFromResource("temporal/scope-check-v1.json", ScopeCheckWorkflowImpl.class);
    }

    private ApplicationFailure failureOf(WorkflowStoreContext context) {
        ScopeCheckWorkflow workflow = client.newWorkflowStub(ScopeCheckWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.MAIN)
                .setWorkflowId("scope-check-test-" + UUID.randomUUID())
                .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                .build());
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> StoreContextPropagator.callWith(context, workflow::check));
        assertThat(thrown).isInstanceOf(WorkflowFailedException.class);
        assertThat(thrown.getCause()).isInstanceOf(ActivityFailure.class);
        assertThat(((ActivityFailure) thrown.getCause()).getCause()).isInstanceOf(ApplicationFailure.class);
        ApplicationFailure failure = (ApplicationFailure) thrown.getCause().getCause();
        // One attempt only: retries would not change a scope decision.
        WorkflowStub stub = WorkflowStub.fromTyped(workflow);
        long attempts = client.fetchHistory(stub.getExecution().getWorkflowId()).getEvents().stream()
                .filter(e -> e.hasActivityTaskStartedEventAttributes())
                .mapToLong(e -> e.getActivityTaskStartedEventAttributes().getAttempt())
                .max().orElse(0);
        assertThat(attempts).isEqualTo(1);
        return failure;
    }
}
