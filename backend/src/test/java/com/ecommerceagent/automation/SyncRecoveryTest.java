package com.ecommerceagent.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.sync.WooImportWorkflow;
import com.ecommerceagent.automation.temporal.StoreContextPropagator;
import com.ecommerceagent.automation.temporal.WorkflowStoreContext;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.IntegrationConnection;
import com.ecommerceagent.integration.IntegrationConnections;
import com.ecommerceagent.integration.IntegrationProvider;
import com.ecommerceagent.integration.SyncInProgressException;
import com.ecommerceagent.integration.SyncRun;
import com.ecommerceagent.integration.SyncRunStatus;
import com.ecommerceagent.integration.WooCommerceImport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * FIX-02: no single failure between the run record and Temporal leaves a store blocked, and no failure starts
 * a second import of the same run. Crashes and lost responses are simulated at the workflow start; the
 * reconciler is called directly instead of waiting for its schedule.
 */
@SpringBootTest(properties = {"spring.temporal.test-server.enabled=true", "operator.sync.start-grace=0s"})
@ActiveProfiles("worker")
@Import(TestcontainersConfiguration.class)
class SyncRecoveryTest {

    static final String ISSUER = "https://issuer.test/realms/operator";
    static final UUID ORG = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    static final UUID STORE = UUID.fromString("b1000000-0000-4000-8000-00000000000a");
    static final UUID OWNER = UUID.fromString("b2000000-0000-4000-8000-0000000000a1");
    static final String KEY = "ck_recovery00000000000000000000000000001";
    static final String SECRET = "cs_recovery00000000000000000000000000001";

    @Autowired
    StoreSync sync;
    @Autowired
    SyncRunReconciler reconciler;
    @MockitoSpyBean
    ImportWorkflowStarter starter;
    @Autowired
    WooCommerceImport imports;
    @Autowired
    IntegrationConnections connections;
    @Autowired
    StoreAccess storeAccess;
    @Autowired
    WorkflowClient client;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Clock clock;

    HttpServer woo;
    ExecutorService wooThreads;
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile CountDownLatch stall;

    @BeforeEach
    void setUp() throws IOException {
        reset(starter);
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Recovery org')").param(ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone)
                VALUES (?, ?, 'Recovery store', 'PLN', 'Europe/Warsaw')
                """).params(STORE, ORG).update();
        jdbc.sql("INSERT INTO app_user (id, issuer, subject) VALUES (?, ?, 'recovery-owner')")
                .params(OWNER, ISSUER).update();
        jdbc.sql("INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES (?, ?, ?, 'OWNER')")
                .params(ORG, STORE, OWNER).update();

        stall = null;
        wooThreads = Executors.newCachedThreadPool();
        woo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        woo.setExecutor(wooThreads);
        woo.createContext("/wp-json/wc/v3/", this::handle);
        woo.start();
    }

    /**
     * Stalled requests are dropped, not answered: an activity of an ended workflow must not write while the
     * next test truncates the tables.
     */
    @AfterEach
    void stop() throws InterruptedException {
        woo.stop(0);
        wooThreads.shutdownNow();
        wooThreads.awaitTermination(5, TimeUnit.SECONDS);
    }

    TrustedStoreScope owner() {
        return storeAccess.scopeFor(OWNER, STORE).orElseThrow();
    }

    TrustedStoreScope system() {
        return storeAccess.systemScope(StoreSync.IMPORT_SERVICE, ORG, STORE).orElseThrow();
    }

    UUID connectedIntegration() {
        IntegrationConnection connection = connections.create(owner(), IntegrationProvider.WOOCOMMERCE,
                "http://127.0.0.1:" + woo.getAddress().getPort());
        connections.saveWooCommerceCredentials(owner(), connection.id(), KEY, SECRET);
        assertThat(connections.check(owner(), connection.id()).error()).isNull();
        requests.clear();
        return connection.id();
    }

    String await(UUID runId) {
        try {
            return client.newUntypedWorkflowStub(StoreSync.workflowId(runId)).getResult(30, TimeUnit.SECONDS,
                    String.class);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("Workflow of run " + runId + " did not finish", e);
        }
    }

    SyncRun run(UUID runId) {
        return imports.run(owner(), runId);
    }

    Instant workflowStartedAt(UUID runId) {
        return jdbc.sql("SELECT workflow_started_at FROM sync_run WHERE id = ?").param(runId)
                .query(Instant.class).optional().orElse(null);
    }

    long catalogReads() {
        return requests.stream().filter(r -> r.startsWith("/wp-json/wc/v3/products?")).count();
    }

    long finishedAudits(UUID runId) {
        return jdbc.sql("""
                SELECT count(*) FROM audit_event WHERE action = 'INTEGRATION_SYNC_FINISHED' AND resource_id = ?
                """).param(runId.toString()).query(Long.class).single();
    }

    /** Process dies after the run record is written and before the workflow start reaches Temporal. */
    @Test
    void crashBeforeTheStartIsRecoveredByTheReconciler() {
        UUID integration = connectedIntegration();
        SyncRun run = imports.createInitialImport(owner(), integration);

        assertThat(starter.status(run.id())).isEmpty();
        assertThatThrownBy(() -> sync.startInitialImport(owner(), integration))
                .isInstanceOf(SyncInProgressException.class);

        reconciler.reconcile();

        assertThat(workflowStartedAt(run.id())).isNotNull();
        assertThat(await(run.id())).isEqualTo("SUCCEEDED");
        assertThat(run(run.id()).status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(catalogReads()).isEqualTo(1);
    }

    /** Temporal is unavailable at request time: the request is accepted, the run waits for the reconciler. */
    @Test
    void failedStartIsNotTakenAsProofThatNoWorkflowRuns() {
        UUID integration = connectedIntegration();
        doThrow(new IllegalStateException("Temporal unavailable")).when(starter).start(any(), any(), any());

        SyncRun run = sync.startInitialImport(owner(), integration);

        assertThat(run(run.id()).status()).isEqualTo(SyncRunStatus.RUNNING);
        assertThat(workflowStartedAt(run.id())).isNull();
        reset(starter);
        reconciler.reconcile();
        assertThat(await(run.id())).isEqualTo("SUCCEEDED");
    }

    /**
     * The start reached Temporal, but its response was lost. The old code marked the run FAILED, which freed the
     * unique index while the workflow kept running, so a second import could run beside it.
     */
    @Test
    void lostStartResponseNeitherFailsTheRunNorStartsASecondWorkflow() {
        UUID integration = connectedIntegration();
        stall = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("deadline exceeded while waiting for the start response");
        }).when(starter).start(any(), any(), any());

        SyncRun run = sync.startInitialImport(owner(), integration);

        assertThat(run(run.id()).status()).isEqualTo(SyncRunStatus.RUNNING);
        assertThat(starter.status(run.id())).contains(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
        assertThatThrownBy(() -> sync.startInitialImport(owner(), integration))
                .isInstanceOf(SyncInProgressException.class);

        reset(starter);
        reconciler.reconcile();
        assertThat(workflowStartedAt(run.id())).isNotNull();
        stall.countDown();

        assertThat(await(run.id())).isEqualTo("SUCCEEDED");
        // A start of a closed run's workflow ID is rejected, not run again.
        starter.start(run.id(), system(), null);
        assertThat(starter.status(run.id())).contains(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED);
        assertThat(finishedAudits(run.id())).isEqualTo(1);
    }

    /** The workflow hits its execution timeout before its own finish step: the run must not stay RUNNING. */
    @Test
    void workflowTimeoutClosesTheRunAndFreesTheStore() {
        UUID integration = connectedIntegration();
        stall = new CountDownLatch(1);
        SyncRun run = imports.createInitialImport(owner(), integration);
        WooImportWorkflow workflow = client.newWorkflowStub(WooImportWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.MAIN)
                .setWorkflowId(StoreSync.workflowId(run.id()))
                .setWorkflowExecutionTimeout(Duration.ofSeconds(2))
                .build());
        StoreContextPropagator.callWith(WorkflowStoreContext.of(system(), null),
                () -> WorkflowClient.start(workflow::run, run.id().toString()));
        imports.markWorkflowStarted(system(), run.id());

        assertThatThrownBy(() -> await(run.id())).isInstanceOf(WorkflowFailedException.class);
        assertThat(run(run.id()).status()).isEqualTo(SyncRunStatus.RUNNING);

        reconciler.reconcile();

        SyncRun closed = run(run.id());
        assertThat(closed.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(closed.errorCode()).isEqualTo("WORKFLOW_TIMED_OUT");
        assertThat(closed.finishedAt()).isNotNull();
        stall.countDown();
        stall = null;
        SyncRun next = sync.startInitialImport(owner(), integration);
        assertThat(await(next.id())).isEqualTo("SUCCEEDED");
    }

    @Test
    void cancelledAndTerminatedWorkflowsCloseTheirRuns() {
        UUID integration = connectedIntegration();
        stall = new CountDownLatch(1);

        SyncRun cancelled = sync.startInitialImport(owner(), integration);
        client.newUntypedWorkflowStub(StoreSync.workflowId(cancelled.id())).cancel();
        assertThatThrownBy(() -> await(cancelled.id())).isInstanceOf(WorkflowFailedException.class);
        reconciler.reconcile();
        assertThat(run(cancelled.id()).errorCode()).isEqualTo("WORKFLOW_CANCELED");

        SyncRun terminated = sync.startInitialImport(owner(), integration);
        client.newUntypedWorkflowStub(StoreSync.workflowId(terminated.id())).terminate("test");
        reconciler.reconcile();
        assertThat(run(terminated.id()).status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(run(terminated.id()).errorCode()).isEqualTo("WORKFLOW_TERMINATED");
    }

    /** An application restart re-runs reconciliation while the import is in progress: nothing changes. */
    @Test
    void reconciliationDuringARunningImportLeavesItAlone() {
        UUID integration = connectedIntegration();
        stall = new CountDownLatch(1);
        SyncRun run = sync.startInitialImport(owner(), integration);
        assertThat(workflowStartedAt(run.id())).isNotNull();

        reconciler.reconcile();
        reconciler.reconcile();

        assertThat(run(run.id()).status()).isEqualTo(SyncRunStatus.RUNNING);
        stall.countDown();
        assertThat(await(run.id())).isEqualTo("SUCCEEDED");
        assertThat(run(run.id()).status()).isEqualTo(SyncRunStatus.SUCCEEDED);
    }

    /** A run whose workflow never started within the deadline is closed, and a late start imports nothing. */
    @Test
    void expiredStartIsClosedAndALateWorkflowDoesNotImport() {
        UUID integration = connectedIntegration();
        SyncRun run = imports.createInitialImport(owner(), integration);
        jdbc.sql("UPDATE sync_run SET started_at = now() - interval '1 hour' WHERE id = ?").param(run.id()).update();

        reconciler.reconcile();

        SyncRun closed = run(run.id());
        assertThat(closed.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(closed.errorCode()).isEqualTo(SyncRunReconciler.START_FAILED);

        // A start that was still in flight lands now: the steps refuse the closed run.
        starter.start(run.id(), system(), null);
        assertThat(await(run.id())).isEqualTo("FAILED");
        assertThat(run(run.id()).errorCode()).isEqualTo(SyncRunReconciler.START_FAILED);
        assertThat(catalogReads()).isZero();
        assertThat(finishedAudits(run.id())).isEqualTo(1);
    }

    /** Several workers reconcile at once: one workflow, one outcome, one audit entry. */
    @Test
    void concurrentReconciliationStartsOneWorkflow() throws Exception {
        UUID integration = connectedIntegration();
        SyncRun pending = imports.createInitialImport(owner(), integration);
        int workers = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> passes = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                passes.add(pool.submit(() -> {
                    go.await();
                    reconciler.reconcile();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> pass : passes) {
                pass.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(await(pending.id())).isEqualTo("SUCCEEDED");
        assertThat(catalogReads()).isEqualTo(1);
        assertThat(finishedAudits(pending.id())).isEqualTo(1);
    }

    @Test
    void concurrentClosingOfAnEndedWorkflowRecordsOneOutcome() throws Exception {
        UUID integration = connectedIntegration();
        stall = new CountDownLatch(1);
        SyncRun run = sync.startInitialImport(owner(), integration);
        client.newUntypedWorkflowStub(StoreSync.workflowId(run.id())).terminate("test");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> passes = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                passes.add(pool.submit(() -> {
                    go.await();
                    reconciler.reconcile();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> pass : passes) {
                pass.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(run(run.id()).errorCode()).isEqualTo("WORKFLOW_TERMINATED");
        assertThat(finishedAudits(run.id())).isEqualTo(1);
    }

    // --- stub WooCommerce: one simple product and one order ---------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().toString();
        String path = exchange.getRequestURI().getPath().substring("/wp-json/wc/v3/".length());
        if (!"settings/general/woocommerce_currency".equals(path)) {
            requests.add(uri);
            CountDownLatch gate = stall;
            if (gate != null) {
                try {
                    gate.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        switch (path) {
            case "products" -> respond(exchange, """
                    [{"id":20,"name":"Kubek","sku":"MUG","type":"simple","status":"publish",
                      "date_modified_gmt":"2026-11-01T10:00:00","manage_stock":true,"stock_quantity":49,
                      "stock_status":"instock","backorders":"no"}]""", 1);
            case "orders" -> respond(exchange, "[" + order() + "]", 1);
            case "settings/general/woocommerce_currency" -> respond(exchange, "{\"value\":\"PLN\"}", null);
            default -> respond(exchange, "[]", 0);
        }
    }

    private String order() {
        String date = LocalDateTime.ofInstant(clock.instant().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                ZoneOffset.UTC).toString();
        return """
                {"id":7000,"status":"completed","currency":"PLN","total":"39.00","total_tax":"7.29",
                 "discount_total":"0.00","shipping_total":"0.00","date_created_gmt":"%s","date_paid_gmt":"%s",
                 "date_completed_gmt":null,"date_modified_gmt":"%s","line_items":[{"id":1,"product_id":20,
                 "variation_id":0,"sku":"MUG","quantity":1,"total":"39.00","total_tax":"7.29"}]}"""
                .formatted(date, date, date);
    }

    private static void respond(HttpExchange exchange, String body, Integer total) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        if (total != null) {
            exchange.getResponseHeaders().set("X-WP-Total", total.toString());
        }
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
