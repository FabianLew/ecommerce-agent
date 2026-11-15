package com.ecommerceagent.automation.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.events.EventDispatch;
import com.ecommerceagent.automation.temporal.StoreContextPropagator;
import com.ecommerceagent.automation.temporal.WorkflowStoreContext;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.OrderCoverage;
import com.ecommerceagent.commerce.OrderRepository;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.IntegrationConnection;
import com.ecommerceagent.integration.IntegrationConnections;
import com.ecommerceagent.integration.IntegrationHealth;
import com.ecommerceagent.integration.IntegrationHealthService;
import com.ecommerceagent.integration.IntegrationProvider;
import com.ecommerceagent.integration.WooHistoryImport;
import com.ecommerceagent.integration.WooHistoryImport.HistoryProgress;
import com.ecommerceagent.integration.WooHistoryImport.HistoryStep;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Order history import (B-13b, ADR-0015) against a stub WooCommerce that filters orders by the query's dates: it
 * reads backward in chunks to 12 months, moves its own watermark only on a matching count, extends the coverage
 * only at its start, and leaves Integration Health and the outbox alone.
 */
@SpringBootTest(properties = "spring.temporal.test-server.enabled=true")
@ActiveProfiles("worker")
@Import(TestcontainersConfiguration.class)
class HistoryImportTest {

    static final String ISSUER = "https://issuer.test/realms/operator";
    static final UUID ORG = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    static final UUID STORE = UUID.fromString("d1000000-0000-4000-8000-00000000000a");
    static final UUID OTHER_STORE = UUID.fromString("d1000000-0000-4000-8000-00000000000b");
    static final UUID OWNER = UUID.fromString("d2000000-0000-4000-8000-0000000000a1");
    static final String KEY = "ck_hist0000000000000000000000000000000001";
    static final String SECRET = "cs_hist0000000000000000000000000000000001";

    @Autowired
    WooHistoryImport history;
    @Autowired
    IntegrationConnections connections;
    @Autowired
    IntegrationHealthService healthService;
    @Autowired
    OrderRepository orders;
    @Autowired
    StoreAccess storeAccess;
    @Autowired
    WorkflowClient client;
    @Autowired
    FixtureDb jdbc;

    HttpServer woo;
    final List<String> requests = new CopyOnWriteArrayList<>();
    /** Orders of the stub shop: id → placed at. */
    final Map<Integer, Instant> shop = new HashMap<>();
    final AtomicReference<Long> totalOverride = new AtomicReference<>();
    TrustedStoreScope scope;
    UUID connectionId;
    Instant windowFrom;
    Instant windowUntil;
    Instant target;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'History org')").param(ORG).update();
        jdbc.sql("INSERT INTO store (id, organization_id, name, currency, timezone) VALUES (?, ?, 'H', 'PLN', 'UTC'), "
                + "(?, ?, 'Other', 'PLN', 'UTC')").params(STORE, ORG, OTHER_STORE, ORG).update();
        jdbc.sql("INSERT INTO app_user (id, issuer, subject) VALUES (?, ?, 'history-owner')").params(OWNER, ISSUER)
                .update();
        jdbc.sql("INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES (?, ?, ?, 'OWNER')")
                .params(ORG, STORE, OWNER).update();

        woo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        woo.createContext("/wp-json/wc/v3/", this::handle);
        woo.start();

        TrustedStoreScope owner = storeAccess.scopeFor(OWNER, STORE).orElseThrow();
        IntegrationConnection connection = connections.create(owner, IntegrationProvider.WOOCOMMERCE,
                "http://127.0.0.1:" + woo.getAddress().getPort());
        connections.saveWooCommerceCredentials(owner, connection.id(), KEY, SECRET);
        assertThat(connections.check(owner, connection.id()).error()).isNull();
        connectionId = connection.id();
        scope = storeAccess.systemScope(EventDispatch.SYNC_SERVICE, ORG, STORE).orElseThrow();

        windowUntil = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(30));
        LocalDate day = LocalDate.ofInstant(windowUntil, ZoneOffset.UTC);
        windowFrom = day.minusDays(14).atStartOfDay(ZoneOffset.UTC).toInstant();
        target = day.minusMonths(12).atStartOfDay(ZoneOffset.UTC).toInstant();
        // One order a week over 13 months; the first lies before the target and must not be imported.
        for (int i = 0; i < 57; i++) {
            shop.put(1000 + i, windowFrom.minus(Duration.ofDays(7L * i + 1)));
        }
        requests.clear();
    }

    @AfterEach
    void stop() {
        woo.stop(0);
    }

    @Test
    void readsBackwardInChunksToTwelveMonthsAndOnlyExtendsTheCoverageStart() {
        importSucceeded();
        orders.recordCoverage(scope, CommerceSource.WOOCOMMERCE, windowFrom, windowUntil);
        IntegrationHealth before = health();
        int outboxBefore = count("outbox_event");
        // The audit trail outlives the truncated test stores, so count only what this test adds.
        int completedBefore = audits("INTEGRATION_HISTORY_COMPLETED");
        assertThat(history.progress(scope, connectionId))
                .isEqualTo(new HistoryProgress(HistoryProgress.Status.IN_PROGRESS, windowFrom, target));

        List<HistoryStep> steps = new ArrayList<>();
        HistoryStep step;
        do {
            step = history.importNext(scope, connectionId, () -> { });
            steps.add(step);
        } while (step.outcome() == HistoryStep.Outcome.IMPORTED && steps.size() < 20);

        // 351 days in 30-day chunks: 12 steps, each starting where the previous one ended.
        assertThat(steps).hasSize(12);
        assertThat(steps.getLast().outcome()).isEqualTo(HistoryStep.Outcome.COMPLETED);
        assertThat(steps.get(0).importedFrom()).isEqualTo(windowFrom.minus(Duration.ofDays(30)));
        assertThat(steps.getLast().importedFrom()).isEqualTo(target);
        long expected = shop.values().stream().filter(at -> !at.isBefore(target)).count();
        assertThat(count("customer_order")).isEqualTo((int) expected);
        assertThat(steps.stream().mapToInt(HistoryStep::orders).sum()).isEqualTo((int) expected);

        OrderCoverage coverage = orders.coverage(scope, CommerceSource.WOOCOMMERCE).orElseThrow();
        assertThat(coverage.completeFrom()).isEqualTo(target);
        assertThat(coverage.completeUntil()).isEqualTo(windowUntil);
        assertThat(watermark()).isEqualTo(target);
        assertThat(history.progress(scope, connectionId).status()).isEqualTo(HistoryProgress.Status.COMPLETE);

        // History is invisible to readiness and to detection.
        IntegrationHealth after = health();
        assertThat(after.status()).isEqualTo(before.status());
        assertThat(after.reasons()).isEqualTo(before.reasons());
        assertThat(after.lastPollAt()).isEqualTo(before.lastPollAt());
        assertThat(count("outbox_event")).isEqualTo(outboxBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM sync_check WHERE kind = 'HISTORY' AND status = 'SUCCEEDED'")
                .query(Integer.class).single()).isEqualTo(12);
        assertThat(audits("INTEGRATION_HISTORY_COMPLETED")).isEqualTo(completedBefore + 1);

        // Done: the next run reads nothing from the shop.
        requests.clear();
        assertThat(history.importNext(scope, connectionId, () -> { }).outcome())
                .isEqualTo(HistoryStep.Outcome.ALREADY_COMPLETE);
        assertThat(requests).isEmpty();
        assertThat(audits("INTEGRATION_HISTORY_COMPLETED")).isEqualTo(completedBefore + 1);
    }

    @Test
    void mismatchedCountMovesNothingAndTheNextStepReadsTheSameChunk() {
        importSucceeded();
        orders.recordCoverage(scope, CommerceSource.WOOCOMMERCE, windowFrom, windowUntil);
        totalOverride.set(99L);

        HistoryStep step = history.importNext(scope, connectionId, () -> { });

        assertThat(step.outcome()).isEqualTo(HistoryStep.Outcome.INCOMPLETE);
        assertThat(watermark()).isNull();
        assertThat(orders.coverage(scope, CommerceSource.WOOCOMMERCE).orElseThrow().completeFrom())
                .isEqualTo(windowFrom);
        assertThat(jdbc.sql("SELECT error_code FROM sync_check WHERE kind = 'HISTORY'").query(String.class).single())
                .isEqualTo("ORDER_COUNT_MISMATCH");

        totalOverride.set(null);
        requests.clear();
        assertThat(history.importNext(scope, connectionId, () -> { }).outcome())
                .isEqualTo(HistoryStep.Outcome.IMPORTED);
        assertThat(requests).anyMatch(r -> r.contains("before=" + wooDate(windowFrom)));
    }

    @Test
    void coverageThatNoLongerTouchesTheHistoryIsNotReplaced() {
        importSucceeded();
        // Coverage restarted after a gap (ADR-0010): it begins an hour ago.
        Instant restarted = windowUntil.minus(Duration.ofHours(1));
        orders.recordCoverage(scope, CommerceSource.WOOCOMMERCE, restarted, windowUntil);

        assertThat(history.importNext(scope, connectionId, () -> { }).outcome())
                .isEqualTo(HistoryStep.Outcome.IMPORTED);

        OrderCoverage coverage = orders.coverage(scope, CommerceSource.WOOCOMMERCE).orElseThrow();
        assertThat(coverage.completeFrom()).isEqualTo(restarted);
        assertThat(coverage.completeUntil()).isEqualTo(windowUntil);
        assertThat(watermark()).isEqualTo(windowFrom.minus(Duration.ofDays(30)));
    }

    @Test
    void nothingHappensBeforeTheInitialImportAndOtherStoresSeeNoProgress() {
        assertThat(history.importNext(scope, connectionId, () -> { }).outcome())
                .isEqualTo(HistoryStep.Outcome.NOT_READY);
        assertThat(requests).isEmpty();

        importSucceeded();
        history.importNext(scope, connectionId, () -> { });
        TrustedStoreScope other = storeAccess.systemScope(EventDispatch.SYNC_SERVICE, ORG, OTHER_STORE).orElseThrow();
        assertThat(history.progress(other, connectionId).status())
                .isEqualTo(HistoryProgress.Status.WAITING_FOR_IMPORT);
    }

    @Test
    void scheduledWorkflowImportsChunksUntilTheTargetInOneRun() {
        importSucceeded();
        orders.recordCoverage(scope, CommerceSource.WOOCOMMERCE, windowFrom, windowUntil);
        String id = SyncSchedules.scheduleId(connectionId, "HISTORY");

        String result = StoreContextPropagator.callWith(WorkflowStoreContext.of(scope, "test-" + id), () ->
                client.newWorkflowStub(HistoryImportWorkflow.class, WorkflowOptions.newBuilder()
                        .setWorkflowId(id)
                        .setTaskQueue(TaskQueues.MAIN)
                        .build()).run(connectionId.toString(), 20, 0));

        assertThat(result).isEqualTo("COMPLETED");
        assertThat(id).isEqualTo("woo-history-" + connectionId);
        assertThat(watermark()).isEqualTo(target);
    }

    private void importSucceeded() {
        jdbc.sql("""
                        INSERT INTO sync_run (id, organization_id, store_id, connection_id, kind, status, window_from,
                                              window_until, started_at, finished_at, workflow_started_at)
                        VALUES (?, ?, ?, ?, 'INITIAL_IMPORT', 'SUCCEEDED', ?, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), ORG, STORE, connectionId, Timestamp.from(windowFrom),
                        Timestamp.from(windowUntil), Timestamp.from(windowUntil), Timestamp.from(windowUntil),
                        Timestamp.from(windowUntil))
                .update();
    }

    private IntegrationHealth health() {
        return healthService.health(scope, connections.get(scope, connectionId));
    }

    private Instant watermark() {
        return jdbc.sql("SELECT watermark FROM sync_watermark WHERE connection_id = ? AND stream = 'HISTORY'")
                .params(connectionId).query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE store_id = ?").params(STORE).query(Integer.class)
                .single();
    }

    private int audits(String action) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE store_id = ? AND action = ?").params(STORE, action)
                .query(Integer.class).single();
    }

    private static String wooDate(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC).toString();
    }

    private static Instant parse(String wooDate) {
        return LocalDateTime.parse(wooDate).toInstant(ZoneOffset.UTC);
    }

    private static String orderJson(int id, Instant placed) {
        return """
                {"id":%d,"status":"completed","currency":"PLN","total":"79.00","total_tax":"14.77",\
                "discount_total":"0.00","shipping_total":"0.00","date_created_gmt":"%s","date_modified_gmt":"%s",\
                "line_items":[{"id":%d,"product_id":14,"variation_id":0,"quantity":1,"total":"64.23","total_tax":"14.77"}]}"""
                .formatted(id, wooDate(placed), wooDate(placed), id);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring("/wp-json/wc/v3/".length());
        String query = exchange.getRequestURI().getRawQuery();
        requests.add(query == null ? path : path + "?" + URLDecoder.decode(query, StandardCharsets.UTF_8));
        Map<String, String> params = new HashMap<>();
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                params.put(kv[0], kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        String body;
        Long total = null;
        if (path.equals("orders")) {
            Instant after = params.containsKey("after") ? parse(params.get("after")) : Instant.MIN;
            Instant before = params.containsKey("before") ? parse(params.get("before")) : Instant.MAX;
            List<String> items = shop.entrySet().stream()
                    .filter(e -> e.getValue().isAfter(after) && e.getValue().isBefore(before))
                    .sorted(Map.Entry.comparingByValue())
                    .map(e -> orderJson(e.getKey(), e.getValue()))
                    .toList();
            total = totalOverride.get() != null ? totalOverride.get() : (long) items.size();
            boolean laterPage = !"1".equals(params.getOrDefault("page", "1"));
            body = laterPage ? "[]" : "[" + String.join(",", items) + "]";
        } else if (path.equals("settings/general/woocommerce_currency")) {
            body = "{\"value\":\"PLN\"}";
        } else {
            body = "[]";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        if (total != null) {
            exchange.getResponseHeaders().set("X-WP-Total", Long.toString(total));
            exchange.getResponseHeaders().set("X-WP-TotalPages", "1");
        }
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
