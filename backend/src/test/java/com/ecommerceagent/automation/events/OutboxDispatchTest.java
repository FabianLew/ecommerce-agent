package com.ecommerceagent.automation.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.guardian.InventoryGuardianWorkflow;
import com.ecommerceagent.automation.inventory.VariantInventoryStatus;
import com.ecommerceagent.automation.inventory.VariantInventoryWorkflow;
import com.ecommerceagent.automation.inventory.VariantInventoryWorkflowImpl;
import com.ecommerceagent.automation.sync.StoreSyncWorkflow;
import com.ecommerceagent.commerce.Backorders;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.InventoryReading;
import com.ecommerceagent.commerce.InventoryRepository;
import com.ecommerceagent.commerce.ObservationSource;
import com.ecommerceagent.commerce.OrderData;
import com.ecommerceagent.commerce.OrderLineData;
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
import com.ecommerceagent.integration.IntegrationProvider;
import com.ecommerceagent.integration.OutboxEvents;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.temporal.client.WorkflowClient;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Outbox → Temporal (B-15, ADR-0010): a repeated event or dispatch never creates a second workflow or a second
 * handled signal, order changes reach each variant, and uncertain stock is settled with one fresh read.
 */
@SpringBootTest(properties = "spring.temporal.test-server.enabled=true")
@ActiveProfiles("worker")
@Import(TestcontainersConfiguration.class)
class OutboxDispatchTest {

    static final String ISSUER = "https://issuer.test/realms/operator";
    static final UUID ORG = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    static final UUID STORE = UUID.fromString("b1000000-0000-4000-8000-00000000000a");
    static final UUID OWNER = UUID.fromString("b2000000-0000-4000-8000-0000000000a1");
    static final String KEY = "ck_dispatch00000000000000000000000000000001";
    static final String SECRET = "cs_dispatch00000000000000000000000000000001";
    static final Instant T0 = Instant.parse("2026-11-14T08:00:00Z");

    @Autowired
    EventDispatch dispatch;
    @Autowired
    OutboxEvents outbox;
    @Autowired
    IntegrationConnections connections;
    @Autowired
    CatalogRepository catalog;
    @Autowired
    InventoryRepository inventory;
    @Autowired
    StoreAccess storeAccess;
    @Autowired
    WorkflowClient client;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    OrderRepository orders;
    @Autowired
    Clock clock;

    HttpServer woo;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final Map<String, String> resources = new ConcurrentHashMap<>();
    TrustedStoreScope scope;
    UUID mug;
    UUID connectionId;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Dispatch org')").param(ORG).update();
        jdbc.sql("INSERT INTO store (id, organization_id, name, currency, timezone) VALUES (?, ?, 'S', 'PLN', 'UTC')")
                .params(STORE, ORG).update();
        jdbc.sql("INSERT INTO app_user (id, issuer, subject) VALUES (?, ?, 'dispatch-owner')").params(OWNER, ISSUER)
                .update();
        jdbc.sql("INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES (?, ?, ?, 'OWNER')")
                .params(ORG, STORE, OWNER).update();

        woo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        woo.createContext("/wp-json/wc/v3/", this::handle);
        woo.start();
        resources.clear();
        resources.put("products", "[]");
        resources.put("orders", "[]");
        resources.put("settings/general/woocommerce_currency", "{\"value\":\"PLN\"}");
        resources.put("products/14", """
                {"id":14,"name":"Kubek","sku":"MUG","type":"simple","status":"publish","manage_stock":true,
                 "stock_quantity":3,"stock_status":"instock","backorders":"no","date_modified_gmt":"2026-11-14T10:00:00"}
                """);

        TrustedStoreScope owner = storeAccess.scopeFor(OWNER, STORE).orElseThrow();
        IntegrationConnection connection = connections.create(owner, IntegrationProvider.WOOCOMMERCE,
                "http://127.0.0.1:" + woo.getAddress().getPort());
        connections.saveWooCommerceCredentials(owner, connection.id(), KEY, SECRET);
        assertThat(connections.check(owner, connection.id()).error()).isNull();
        connectionId = connection.id();
        requests.clear();

        scope = storeAccess.systemScope("dispatch-test", ORG, STORE).orElseThrow();
        catalog.upsertProduct(scope, CommerceSource.WOOCOMMERCE, new ProductData("14", "Kubek", "MUG", "simple",
                "publish", T0));
        mug = catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, new VariantData("14", "14", "Kubek", "MUG",
                "publish", StockTracking.VARIANT, Backorders.NO, T0));
        inventory.record(scope, mug, new InventoryReading(49, StockStatus.IN_STOCK,
                ReservationSemantics.ALREADY_ACCOUNTED_FOR, null, ObservationSource.IMPORT, T0, T0));
    }

    @AfterEach
    void stop() {
        woo.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring("/wp-json/wc/v3/".length());
        requests.add(path);
        String body = resources.get(path);
        byte[] bytes = (body == null ? "{\"code\":\"woocommerce_rest_product_invalid_id\"}" : body)
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void repeatedEventAndRepeatedDispatchReachOneWorkflowOnce() {
        appendInventoryChanged(mug, "2026-11-14T09:00:00Z", "a");
        // The same state appended again (another webhook path) is one outbox row.
        appendInventoryChanged(mug, "2026-11-14T09:00:00Z", "a");
        assertThat(dispatch.dispatchPending(10)).isEqualTo(1);
        String firstRun = runId(mug);

        // Signal went out but the commit was lost: the event is dispatched again.
        jdbc.sql("UPDATE outbox_event SET dispatched_at = NULL").update();
        assertThat(dispatch.dispatchPending(10)).isEqualTo(1);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            VariantInventoryStatus status = status(mug);
            assertThat(status.handled()).isEqualTo(1);
            assertThat(status.duplicates()).isEqualTo(1);
        });
        assertThat(runId(mug)).isEqualTo(firstRun);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE dispatched_at IS NULL").query(Long.class).single())
                .isZero();
    }

    @Test
    void verificationRequestIsSettledByOneFreshRead() {
        Map<String, Object> reason = Map.of("reason", "AMBIGUOUS_SOURCE_TIME", "variantId", mug.toString());
        outbox.append(scope, OutboxEvents.INVENTORY_VERIFICATION_REQUESTED, "variant", mug.toString(), reason, "v1");
        outbox.append(scope, OutboxEvents.INVENTORY_VERIFICATION_REQUESTED, "variant", mug.toString(), reason, "v2");
        dispatch.dispatchPending(10);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(status(mug).verifications()).isEqualTo(1));
        assertThat(requests).containsOnlyOnce("products/14");
        InventoryReading latest = inventory.latest(scope, mug).orElseThrow().reading();
        assertThat(latest.stockQuantity()).isEqualTo(3);
        assertThat(latest.observationSource()).isEqualTo(ObservationSource.POLL);
        // The fresh read is itself a domain event for the variant.
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE event_type = ?")
                .params(OutboxEvents.INVENTORY_CHANGED).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void orderChangeWithoutNewerStockTriggersReadAndWithNewerStockDoesNot() throws Exception {
        UUID other = catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, new VariantData("15", "14", "Kubek XL",
                null, "publish", StockTracking.VARIANT, Backorders.NO, T0));
        resources.put("products/14/variations/15", """
                {"id":15,"status":"publish","manage_stock":true,"stock_quantity":8,"stock_status":"instock",
                 "backorders":"no","date_modified_gmt":"2026-11-14T10:00:00"}
                """);
        outbox.append(scope, OutboxEvents.ORDER_CHANGED, "order", "501", Map.of("orderExternalId", "501",
                "status", "processing", "sourceUpdatedAt", "2026-11-14T10:00:00Z",
                "variantIds", List.of(mug.toString(), other.toString())), "o1");
        // The stock change caused by the order arrives for the mug only.
        appendInventoryChanged(mug, "2026-11-14T10:00:00Z", "b");
        dispatch.dispatchPending(10);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(status(other).verifications()).isEqualTo(1);
            assertThat(status(mug).handled()).isEqualTo(2);
        });
        assertThat(status(mug).verifications()).isZero();
        assertThat(status(mug).verificationPending()).isFalse();
        assertThat(requests).containsExactly("products/14/variations/15");
        assertThat(inventory.latest(scope, other).orElseThrow().reading().stockQuantity()).isEqualTo(8);
        assertThat(catalog.variant(scope, other).orElseThrow().name()).isEqualTo("Kubek XL");

        // The long-running history (signals, timer, activity) replays deterministically.
        WorkflowExecutionHistory history = client.fetchHistory(
                VariantInventoryWorkflow.workflowId(STORE, other.toString()));
        WorkflowReplayer.replayWorkflowExecution(history, VariantInventoryWorkflowImpl.class);
    }

    @Test
    void riskSeriesIsConfirmedByAFreshReadAndOpensOneEpisode() throws Exception {
        // 7 units a day for the last 7 full days and 3 in the shop: well under the 3-day threshold.
        Instant today = clock.instant().truncatedTo(ChronoUnit.DAYS);
        for (int day = 1; day <= 7; day++) {
            Instant placedAt = today.minus(Duration.ofDays(day)).plus(Duration.ofHours(10));
            orders.upsert(scope, CommerceSource.WOOCOMMERCE, new OrderData("r-" + day, "processing", "PLN",
                    new BigDecimal("70.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, placedAt, placedAt,
                    null, placedAt, List.of(new OrderLineData("rl-" + day, "14", "14", "MUG", 7,
                            new BigDecimal("10.00"), BigDecimal.ZERO))));
        }
        orders.recordCoverage(scope, CommerceSource.WOOCOMMERCE, today.minus(Duration.ofDays(30)), clock.instant());
        outbox.append(scope, OutboxEvents.INVENTORY_VERIFICATION_REQUESTED, "variant", mug.toString(),
                Map.of("reason", "AMBIGUOUS_SOURCE_TIME", "variantId", mug.toString()), "r1");
        dispatch.dispatchPending(10);

        // First read: risk, not yet an episode; the detector asks for a confirming read after the gap.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(mug).opportunityId()).isNotNull());
        VariantInventoryStatus status = status(mug);
        assertThat(status.lastRiskOutcome()).isEqualTo("AT_RISK");
        assertThat(status.riskCheckPending()).isFalse();
        assertThat(status.verifications()).isGreaterThanOrEqualTo(2);
        // The confirming read waits for the gap instead of polling the shop.
        assertThat(requests.stream().filter("products/14"::equals).count()).isBetween(2L, 3L);
        assertThat(jdbc.sql("SELECT count(*) FROM opportunity WHERE subject_id = ?").param(mug)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM inventory_risk_observation
                WHERE variant_id = ? AND outcome = 'AT_RISK' AND stock_confirmed_at IS NOT NULL
                """).param(mug).query(Long.class).single()).isGreaterThanOrEqualTo(2);

        // The opened episode started its guardian once; with no ad promoting the mug there is nothing to pause.
        String opportunityId = status.opportunityId();
        InventoryGuardianWorkflow guardian = client.newWorkflowStub(InventoryGuardianWorkflow.class,
                InventoryGuardianWorkflow.workflowId(STORE, mug.toString(), opportunityId));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(guardian.status().finished()).isTrue());
        assertThat(guardian.status().analyses()).isEqualTo(1);
        assertThat(guardian.status().disposition()).isEqualTo("NO_ACTION");
        assertThat(jdbc.sql("SELECT status || ':' || close_reason FROM opportunity WHERE id = ?::uuid")
                .param(opportunityId).query(String.class).single()).isEqualTo("CLOSED:NO_ACTION");

        WorkflowExecutionHistory history = client.fetchHistory(VariantInventoryWorkflow.workflowId(STORE,
                mug.toString()));
        WorkflowReplayer.replayWorkflowExecution(history, VariantInventoryWorkflowImpl.class);
        // ./gradlew test -PrecordTemporalFixtures refreshes the fixture after an intentional, versioned change;
        // variant-inventory-v1.json stays as recorded before risk detection.
        if (Boolean.getBoolean("recordTemporalFixtures")) {
            Files.writeString(Path.of("src/test/resources/temporal/variant-inventory-risk-v1.json"),
                    history.toJson(true));
        }
    }

    @Test
    void recordedVariantHistoriesStillReplay() throws Exception {
        // Guards against non-deterministic changes to VariantInventoryWorkflowImpl: a history from before risk
        // detection (B-15) and one with the risk-detection version (B-19).
        WorkflowReplayer.replayWorkflowExecutionFromResource("temporal/variant-inventory-v1.json",
                VariantInventoryWorkflowImpl.class);
        WorkflowReplayer.replayWorkflowExecutionFromResource("temporal/variant-inventory-risk-v1.json",
                VariantInventoryWorkflowImpl.class);
    }

    @Test
    void unknownProductIsReadIntoTheCatalog() {
        resources.put("products/30", """
                {"id":30,"name":"Bluza","type":"variable","status":"publish","manage_stock":false,
                 "stock_status":"instock","date_modified_gmt":"2026-11-14T10:00:00"}
                """);
        resources.put("products/30/variations", """
                [{"id":31,"sku":"HOODIE-S","status":"publish","manage_stock":true,"stock_quantity":4,
                  "stock_status":"instock","backorders":"no","date_modified_gmt":"2026-11-14T10:00:00",
                  "attributes":[{"name":"Rozmiar","option":"S"}]}]
                """);
        outbox.append(scope, OutboxEvents.INVENTORY_VERIFICATION_REQUESTED, "product", "30",
                Map.of("reason", "UNKNOWN_PRODUCT", "productExternalId", "30"), "p1");
        outbox.append(scope, OutboxEvents.INVENTORY_VERIFICATION_REQUESTED, "product", "30",
                Map.of("reason", "UNKNOWN_PRODUCT", "productExternalId", "30"), "p2");
        dispatch.dispatchPending(10);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(catalog.variantByExternalId(scope, CommerceSource.WOOCOMMERCE, "31")).isPresent());
        var hoodie = catalog.variantByExternalId(scope, CommerceSource.WOOCOMMERCE, "31").orElseThrow();
        assertThat(hoodie.name()).isEqualTo("Bluza – S");
        assertThat(inventory.latest(scope, hoodie.id()).orElseThrow().reading().stockQuantity()).isEqualTo(4);
        // Two requests within the delay are one read.
        assertThat(requests.stream().filter("products/30"::equals)).hasSize(1);
        assertThat(client.newWorkflowStub(StoreSyncWorkflow.class, StoreSyncWorkflow.workflowId(connectionId))
                .refreshed()).isEqualTo(1);
    }

    @Test
    void failedDispatchIsPostponedNotLost() {
        outbox.append(scope, OutboxEvents.INVENTORY_CHANGED, "variant", "not-a-variant-id",
                Map.of("sourceUpdatedAt", "2026-11-14T09:00:00Z"), "broken");
        assertThat(dispatch.dispatchPending(10)).isEqualTo(1);

        Map<String, Object> row = jdbc.sql("SELECT attempts, dispatched_at, next_attempt_at > now() AS later FROM outbox_event")
                .query().singleRow();
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("dispatched_at")).isNull();
        assertThat(row.get("later")).isEqualTo(true);
        // Not due yet: the next round leaves it alone.
        assertThat(dispatch.dispatchPending(10)).isZero();
    }

    private void appendInventoryChanged(UUID variant, String sourceUpdatedAt, String key) {
        outbox.append(scope, OutboxEvents.INVENTORY_CHANGED, "variant", variant.toString(),
                Map.of("variantId", variant.toString(), "sourceUpdatedAt", sourceUpdatedAt), "inventory:" + key);
    }

    private VariantInventoryStatus status(UUID variant) {
        return client.newWorkflowStub(VariantInventoryWorkflow.class,
                VariantInventoryWorkflow.workflowId(STORE, variant.toString())).status();
    }

    private String runId(UUID variant) {
        return client.newUntypedWorkflowStub(VariantInventoryWorkflow.workflowId(STORE, variant.toString()))
                .describe().getExecution().getRunId();
    }
}
