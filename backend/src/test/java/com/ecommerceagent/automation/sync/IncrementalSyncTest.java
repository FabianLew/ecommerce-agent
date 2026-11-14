package com.ecommerceagent.automation.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.events.EventDispatch;
import com.ecommerceagent.automation.temporal.StoreContextPropagator;
import com.ecommerceagent.automation.temporal.WorkflowStoreContext;
import com.ecommerceagent.commerce.Backorders;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.InventoryReading;
import com.ecommerceagent.commerce.InventoryRepository;
import com.ecommerceagent.commerce.ObservationSource;
import com.ecommerceagent.commerce.OrderRepository;
import com.ecommerceagent.commerce.ProductData;
import com.ecommerceagent.commerce.ReservationSemantics;
import com.ecommerceagent.commerce.StockStatus;
import com.ecommerceagent.commerce.StockTracking;
import com.ecommerceagent.commerce.VariantData;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.IntegrationConnection;
import com.ecommerceagent.integration.IntegrationConnections;
import com.ecommerceagent.integration.IntegrationHealth;
import com.ecommerceagent.integration.IntegrationHealth.Reason;
import com.ecommerceagent.integration.IntegrationHealth.Status;
import com.ecommerceagent.integration.IntegrationHealthService;
import com.ecommerceagent.integration.IntegrationProvider;
import com.ecommerceagent.integration.SyncCheck;
import com.ecommerceagent.integration.WooIncrementalSync;
import com.ecommerceagent.integration.WooWebhooks;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Polling, order reconciliation and Integration Health (B-16, ADR-0010) against a stub WooCommerce: watermarks
 * move only after a complete read, coverage extends only on a matching count, missed webhooks are counted.
 */
@SpringBootTest(properties = "spring.temporal.test-server.enabled=true")
@ActiveProfiles("worker")
@Import(TestcontainersConfiguration.class)
class IncrementalSyncTest {

    static final String ISSUER = "https://issuer.test/realms/operator";
    static final UUID ORG = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    static final UUID STORE = UUID.fromString("c1000000-0000-4000-8000-00000000000a");
    static final UUID OWNER = UUID.fromString("c2000000-0000-4000-8000-0000000000a1");
    static final String KEY = "ck_sync0000000000000000000000000000000001";
    static final String SECRET = "cs_sync0000000000000000000000000000000001";

    @Autowired
    WooIncrementalSync sync;
    @Autowired
    IntegrationHealthService healthService;
    @Autowired
    IntegrationConnections connections;
    @Autowired
    WooWebhooks webhooks;
    @Autowired
    CatalogRepository catalog;
    @Autowired
    InventoryRepository inventory;
    @Autowired
    OrderRepository orders;
    @Autowired
    StoreAccess storeAccess;
    @Autowired
    WorkflowClient client;
    @Autowired
    JdbcClient jdbc;

    HttpServer woo;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final Map<String, String> resources = new ConcurrentHashMap<>();
    final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    final Map<String, Long> totals = new ConcurrentHashMap<>();
    TrustedStoreScope scope;
    UUID connectionId;
    UUID mug;
    Instant now;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Sync org')").param(ORG).update();
        jdbc.sql("INSERT INTO store (id, organization_id, name, currency, timezone) VALUES (?, ?, 'S', 'PLN', 'UTC')")
                .params(STORE, ORG).update();
        jdbc.sql("INSERT INTO app_user (id, issuer, subject) VALUES (?, ?, 'sync-owner')").params(OWNER, ISSUER)
                .update();
        jdbc.sql("INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES (?, ?, ?, 'OWNER')")
                .params(ORG, STORE, OWNER).update();

        woo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        woo.createContext("/wp-json/wc/v3/", this::handle);
        woo.start();
        resources.put("products", "[]");
        resources.put("orders", "[]");
        resources.put("settings/general/woocommerce_currency", "{\"value\":\"PLN\"}");

        TrustedStoreScope owner = storeAccess.scopeFor(OWNER, STORE).orElseThrow();
        IntegrationConnection connection = connections.create(owner, IntegrationProvider.WOOCOMMERCE,
                "http://127.0.0.1:" + woo.getAddress().getPort());
        connections.saveWooCommerceCredentials(owner, connection.id(), KEY, SECRET);
        assertThat(connections.check(owner, connection.id()).error()).isNull();
        connectionId = connection.id();
        requests.clear();

        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        scope = storeAccess.systemScope(EventDispatch.SYNC_SERVICE, ORG, STORE).orElseThrow();
        Instant imported = now.minus(Duration.ofMinutes(30));
        catalog.upsertProduct(scope, CommerceSource.WOOCOMMERCE, new ProductData("14", "Kubek", "MUG", "simple",
                "publish", imported));
        mug = catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, new VariantData("14", "14", "Kubek", "MUG",
                "publish", StockTracking.VARIANT, Backorders.NO, imported));
        inventory.record(scope, mug, new InventoryReading(49, StockStatus.IN_STOCK,
                ReservationSemantics.ALREADY_ACCOUNTED_FOR, null, ObservationSource.IMPORT, imported, imported));
    }

    @AfterEach
    void stop() {
        woo.stop(0);
    }

    @Test
    void pollRecordsChangesCountsMissedWebhooksAndMovesTheWatermark() {
        Instant importedAt = importSucceeded(now.minus(Duration.ofMinutes(30)));
        webhookSecretSetAt(now.minus(Duration.ofHours(1)));
        Instant changed = now.minus(Duration.ofMinutes(5));
        resources.put("products", "[" + mugJson(3, changed) + "]");

        SyncCheck check = sync.poll(scope, connectionId);

        assertThat(check.status()).isEqualTo(SyncCheck.Status.SUCCEEDED);
        assertThat(check.changes()).isEqualTo(1);
        assertThat(check.missedWebhooks()).isEqualTo(1);
        // The first poll starts at the import, with an overlap.
        assertThat(requests).anyMatch(r -> r.startsWith("products?")
                && r.contains("modified_after=" + wooDate(importedAt.minus(Duration.ofMinutes(2)))));
        assertThat(watermark("PRODUCTS")).isEqualTo(changed);
        InventoryReading latest = inventory.latest(scope, mug).orElseThrow().reading();
        assertThat(latest.stockQuantity()).isEqualTo(3);
        assertThat(latest.observationSource()).isEqualTo(ObservationSource.POLL);

        // The next poll starts at the watermark; the same state again is no change.
        requests.clear();
        SyncCheck again = sync.poll(scope, connectionId);
        assertThat(again.changes()).isZero();
        assertThat(requests).anyMatch(r -> r.startsWith("products?")
                && r.contains("modified_after=" + wooDate(changed.minus(Duration.ofMinutes(2)))));
    }

    @Test
    void recentChangeAndChangeWithoutWebhookSecretAreNotMissedWebhooks() {
        importSucceeded(now.minus(Duration.ofMinutes(30)));
        resources.put("products", "[" + mugJson(3, now.minus(Duration.ofMinutes(5))) + "]");
        assertThat(sync.poll(scope, connectionId).missedWebhooks()).isZero();

        webhookSecretSetAt(now.minus(Duration.ofHours(1)));
        resources.put("products", "[" + mugJson(2, now.minusSeconds(30)) + "]");
        SyncCheck check = sync.poll(scope, connectionId);
        assertThat(check.changes()).isEqualTo(1);
        // Still within the delivery grace period.
        assertThat(check.missedWebhooks()).isZero();
    }

    @Test
    void failedPageLeavesTheWatermarkWhereItWas() {
        importSucceeded(now.minus(Duration.ofMinutes(30)));
        catalog.upsertProduct(scope, CommerceSource.WOOCOMMERCE, new ProductData("20", "Koszulka", null, "variable",
                "publish", now.minus(Duration.ofMinutes(30))));
        resources.put("products", "[" + mugJson(3, now.minus(Duration.ofMinutes(5))) + "]");
        statuses.put("products/20/variations", 500);

        SyncCheck check = sync.poll(scope, connectionId);

        assertThat(check.status()).isEqualTo(SyncCheck.Status.FAILED);
        assertThat(watermark("PRODUCTS")).isNull();
    }

    @Test
    void reconciliationExtendsCoverageOnlyWhenTheCountMatches() {
        Instant windowUntil = importSucceeded(now.minus(Duration.ofMinutes(90)));
        orders.recordCoverage(scope, CommerceSource.WOOCOMMERCE, windowUntil.minus(Duration.ofDays(14)), windowUntil);

        // The source reports two orders but returns one: nothing moves.
        resources.put("orders", "[" + orderJson(501, now.minus(Duration.ofMinutes(20))) + "]");
        totals.put("orders", 2L);
        SyncCheck incomplete = sync.reconcileOrders(scope, connectionId);
        assertThat(incomplete.status()).isEqualTo(SyncCheck.Status.INCOMPLETE);
        assertThat(incomplete.errorCode()).isEqualTo("ORDER_COUNT_MISMATCH");
        assertThat(completeUntil()).isEqualTo(windowUntil);
        assertThat(watermark("ORDERS")).isNull();
        assertThat(requests).allMatch(r -> !r.startsWith("orders?") || !r.contains("modified_before"));

        totals.put("orders", 1L);
        SyncCheck complete = sync.reconcileOrders(scope, connectionId);
        assertThat(complete.status()).isEqualTo(SyncCheck.Status.SUCCEEDED);
        assertThat(completeUntil()).isEqualTo(complete.startedAt());
        assertThat(watermark("ORDERS")).isEqualTo(complete.startedAt());
        assertThat(jdbc.sql("SELECT status FROM customer_order WHERE store_id = ?").params(STORE).query(String.class)
                .single()).isEqualTo("processing");
    }

    @Test
    void orderMissingFromTheDatabaseIsAMissedWebhook() {
        Instant windowUntil = importSucceeded(now.minus(Duration.ofMinutes(90)));
        orders.recordCoverage(scope, CommerceSource.WOOCOMMERCE, windowUntil.minus(Duration.ofDays(14)), windowUntil);
        webhookSecretSetAt(now.minus(Duration.ofHours(2)));
        resources.put("orders", "[" + orderJson(501, now.minus(Duration.ofMinutes(20))) + "]");
        totals.put("orders", 1L);

        SyncCheck first = sync.reconcileOrders(scope, connectionId);
        assertThat(first.changes()).isEqualTo(1);
        assertThat(first.missedWebhooks()).isEqualTo(1);
        // Read again (overlap): known in the same state, so neither a change nor a missed webhook.
        SyncCheck second = sync.reconcileOrders(scope, connectionId);
        assertThat(second.changes()).isZero();
        assertThat(second.missedWebhooks()).isZero();
    }

    @Test
    void healthGoesFromNotReadyThroughStaleToHealthyAndDegraded() {
        assertThat(health().status()).isEqualTo(Status.NOT_READY);
        assertThat(health().reasons()).containsExactly(Reason.INITIAL_IMPORT_MISSING);

        // An import an hour ago without polling or order coverage is stale.
        importSucceeded(now.minus(Duration.ofHours(1)));
        assertThat(health().status()).isEqualTo(Status.STALE);
        assertThat(health().reasons()).contains(Reason.POLL_STALE, Reason.ORDERS_STALE,
                Reason.WEBHOOK_SECRET_MISSING);

        totals.put("orders", 0L);
        assertThat(sync.poll(scope, connectionId).status()).isEqualTo(SyncCheck.Status.SUCCEEDED);
        assertThat(sync.reconcileOrders(scope, connectionId).status()).isEqualTo(SyncCheck.Status.SUCCEEDED);
        assertThat(health().status()).isEqualTo(Status.DEGRADED);
        assertThat(health().reasons()).containsExactly(Reason.WEBHOOK_SECRET_MISSING);

        webhooks.saveSecret(storeAccess.scopeFor(OWNER, STORE).orElseThrow(), connectionId,
                "0123456789abcdef0123456789abcdef0123456789abcdef");
        assertThat(health().status()).isEqualTo(Status.HEALTHY);
        assertThat(health().reasons()).isEmpty();

        webhookSecretSetAt(now.minus(Duration.ofHours(1)));
        resources.put("products", "[" + mugJson(3, now.minus(Duration.ofMinutes(5))) + "]");
        sync.poll(scope, connectionId);
        IntegrationHealth degraded = health();
        assertThat(degraded.status()).isEqualTo(Status.DEGRADED);
        assertThat(degraded.reasons()).containsExactly(Reason.MISSED_WEBHOOKS);
        assertThat(degraded.missedWebhooks24h()).isEqualTo(1);

        // A failed poll does not refresh the last successful one; old enough, it makes the data stale.
        jdbc.sql("UPDATE sync_check SET started_at = started_at - interval '1 hour', "
                + "finished_at = finished_at - interval '1 hour' WHERE kind = 'POLL'").update();
        statuses.put("products", 500);
        assertThat(sync.poll(scope, connectionId).status()).isEqualTo(SyncCheck.Status.FAILED);
        assertThat(health().status()).isEqualTo(Status.STALE);
        assertThat(health().reasons()).contains(Reason.POLL_STALE);
    }

    @Test
    void scheduledWorkflowRunsThePollWithTheStoreScopeFromItsHeader() {
        importSucceeded(now.minus(Duration.ofMinutes(30)));
        resources.put("products", "[" + mugJson(7, now.minus(Duration.ofMinutes(5))) + "]");
        String id = SyncSchedules.scheduleId(connectionId, "POLL");

        String result = StoreContextPropagator.callWith(WorkflowStoreContext.of(scope, "test-" + id), () ->
                client.newWorkflowStub(IncrementalSyncWorkflow.class, WorkflowOptions.newBuilder()
                        .setWorkflowId(id)
                        .setTaskQueue(TaskQueues.MAIN)
                        .build()).run(connectionId.toString(), "POLL"));

        assertThat(result).isEqualTo("SUCCEEDED");
        assertThat(inventory.latest(scope, mug).orElseThrow().reading().stockQuantity()).isEqualTo(7);
        assertThat(id).isEqualTo("woo-poll-" + connectionId);
        assertThat(SyncSchedules.scheduleId(connectionId, "RECONCILE")).isEqualTo("woo-reconcile-" + connectionId);
    }

    private IntegrationHealth health() {
        return healthService.health(scope, connections.get(scope, connectionId));
    }

    /** A successful initial import that started at the given time; returns its window end. */
    private Instant importSucceeded(Instant startedAt) {
        Instant windowUntil = startedAt;
        jdbc.sql("""
                        INSERT INTO sync_run (id, organization_id, store_id, connection_id, kind, status, window_from,
                                              window_until, started_at, finished_at)
                        VALUES (?, ?, ?, ?, 'INITIAL_IMPORT', 'SUCCEEDED', ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), ORG, STORE, connectionId,
                        Timestamp.from(windowUntil.minus(Duration.ofDays(14))), Timestamp.from(windowUntil),
                        Timestamp.from(startedAt), Timestamp.from(startedAt.plusSeconds(20)))
                .update();
        return windowUntil;
    }

    private void webhookSecretSetAt(Instant at) {
        jdbc.sql("UPDATE integration_connection SET webhook_secret_set_at = ? WHERE id = ?")
                .params(Timestamp.from(at), connectionId).update();
    }

    private Instant watermark(String stream) {
        return jdbc.sql("SELECT watermark FROM sync_watermark WHERE connection_id = ? AND stream = ?")
                .params(connectionId, stream).query(Timestamp.class).optional().map(Timestamp::toInstant)
                .orElse(null);
    }

    private Instant completeUntil() {
        return orders.coverage(scope, CommerceSource.WOOCOMMERCE).orElseThrow().completeUntil();
    }

    private static String wooDate(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC).toString();
    }

    private static String mugJson(int quantity, Instant modified) {
        return """
                {"id":14,"name":"Kubek","sku":"MUG","type":"simple","status":"publish","manage_stock":true,
                 "stock_quantity":%d,"stock_status":"instock","backorders":"no","date_modified_gmt":"%s"}"""
                .formatted(quantity, wooDate(modified));
    }

    private static String orderJson(int id, Instant modified) {
        return """
                {"id":%d,"status":"processing","currency":"PLN","total":"79.00","total_tax":"14.77",\
                "discount_total":"0.00","shipping_total":"0.00","date_created_gmt":"%s","date_modified_gmt":"%s",\
                "line_items":[{"id":9,"product_id":14,"variation_id":0,"quantity":1,"total":"64.23","total_tax":"14.77"}]}"""
                .formatted(id, wooDate(modified.minus(Duration.ofMinutes(1))), wooDate(modified));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring("/wp-json/wc/v3/".length());
        String query = exchange.getRequestURI().getQuery();
        requests.add(query == null ? path : path + "?" + query);
        boolean laterPage = query != null && query.matches(".*(^|&)page=([2-9]|\\d{2,}).*");
        String body = laterPage ? "[]" : resources.get(path);
        int status = statuses.getOrDefault(path, body == null ? 404 : 200);
        if (status >= 500) {
            body = "{\"code\":\"internal_server_error\"}";
        } else if (body == null) {
            body = path.endsWith("/variations") ? "[]" : "{\"code\":\"woocommerce_rest_product_invalid_id\"}";
            status = path.endsWith("/variations") ? 200 : 404;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        Long total = totals.get(path);
        if (total != null) {
            exchange.getResponseHeaders().set("X-WP-Total", Long.toString(total));
            exchange.getResponseHeaders().set("X-WP-TotalPages", "1");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
