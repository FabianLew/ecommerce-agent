package com.ecommerceagent.automation.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.automation.StoreSync;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.InventoryObservation;
import com.ecommerceagent.commerce.InventoryRepository;
import com.ecommerceagent.commerce.OrderCoverage;
import com.ecommerceagent.commerce.OrderRepository;
import com.ecommerceagent.commerce.StockStatus;
import com.ecommerceagent.commerce.StockTracking;
import com.ecommerceagent.commerce.Variant;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StoreAccessDeniedException;
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
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * B-13 (ADR-0008): initial import through the Temporal workflow against a stub WooCommerce, with paging,
 * variations, stock tracking modes, the completeness check and customer-data minimization.
 */
@SpringBootTest(properties = "spring.temporal.test-server.enabled=true")
@ActiveProfiles("worker")
@Import(TestcontainersConfiguration.class)
class WooImportTest {

    static final String ISSUER = "https://issuer.test/realms/operator";
    static final UUID ORG = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    static final UUID STORE = UUID.fromString("a1000000-0000-4000-8000-00000000000a");
    static final UUID OTHER_STORE = UUID.fromString("a1000000-0000-4000-8000-00000000000b");
    static final UUID OWNER = UUID.fromString("a2000000-0000-4000-8000-0000000000a1");
    static final UUID ANALYST = UUID.fromString("a2000000-0000-4000-8000-0000000000a2");
    static final String KEY = "ck_import000000000000000000000000000000001";
    static final String SECRET = "cs_import000000000000000000000000000000001";
    static final int EXTRA_PRODUCTS = 120;
    static final int SIMPLE_ORDERS = 101;

    @Autowired
    StoreSync sync;
    @Autowired
    WooCommerceImport imports;
    @Autowired
    IntegrationConnections connections;
    @Autowired
    StoreAccess storeAccess;
    @Autowired
    WorkflowClient client;
    @Autowired
    CatalogRepository catalog;
    @Autowired
    InventoryRepository inventory;
    @Autowired
    OrderRepository orders;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Clock clock;

    HttpServer woo;
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile Integer reportedOrderTotal;
    volatile boolean rejectKey;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Import org')").param(ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone)
                VALUES (?, ?, 'Import store', 'PLN', 'Europe/Warsaw'), (?, ?, 'Other', 'PLN', 'Europe/Warsaw')
                """).params(STORE, ORG, OTHER_STORE, ORG).update();
        jdbc.sql("""
                INSERT INTO app_user (id, issuer, subject) VALUES (?, ?, 'import-owner'), (?, ?, 'import-analyst')
                """).params(OWNER, ISSUER, ANALYST, ISSUER).update();
        jdbc.sql("""
                INSERT INTO store_membership (organization_id, store_id, user_id, role)
                VALUES (?, ?, ?, 'OWNER'), (?, ?, ?, 'ANALYST'), (?, ?, ?, 'OWNER')
                """).params(ORG, STORE, OWNER, ORG, STORE, ANALYST, ORG, OTHER_STORE, OWNER).update();

        woo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        woo.createContext("/wp-json/wc/v3/", this::handle);
        woo.start();
        reportedOrderTotal = null;
        rejectKey = false;
    }

    @AfterEach
    void stop() {
        woo.stop(0);
    }

    TrustedStoreScope owner() {
        return storeAccess.scopeFor(OWNER, STORE).orElseThrow();
    }

    UUID connectedIntegration() {
        IntegrationConnection connection = connections.create(owner(), IntegrationProvider.WOOCOMMERCE,
                "http://127.0.0.1:" + woo.getAddress().getPort());
        connections.saveWooCommerceCredentials(owner(), connection.id(), KEY, SECRET);
        assertThat(connections.check(owner(), connection.id()).error()).isNull();
        requests.clear();
        return connection.id();
    }

    SyncRun importAndWait(UUID integrationId) {
        SyncRun run = sync.startInitialImport(owner(), integrationId);
        client.newUntypedWorkflowStub(StoreSync.workflowId(run.id())).getResult(String.class);
        return imports.run(owner(), run.id());
    }

    @Test
    void importsCatalogStockAndOrdersOfTheWindow() {
        SyncRun run = importAndWait(connectedIntegration());

        assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(run.productsImported()).isEqualTo(2 + EXTRA_PRODUCTS);
        assertThat(run.variantsImported()).isEqualTo(4 + EXTRA_PRODUCTS);
        assertThat(run.ordersImported()).isEqualTo(SIMPLE_ORDERS + 1).isEqualTo(run.ordersExpected());
        Instant midnight = LocalDateTime.ofInstant(run.windowUntil(), java.time.ZoneId.of("Europe/Warsaw"))
                .toLocalDate().minusDays(14).atStartOfDay(java.time.ZoneId.of("Europe/Warsaw")).toInstant();
        assertThat(run.windowFrom()).isEqualTo(midnight);

        TrustedStoreScope scope = owner();
        Variant tracked = variant(scope, "11");
        Variant parent = variant(scope, "12");
        Variant untracked = variant(scope, "13");
        assertThat(tracked.stockTracking()).isEqualTo(StockTracking.VARIANT);
        assertThat(tracked.name()).isEqualTo("Koszulka – S");
        assertThat(parent.stockTracking()).isEqualTo(StockTracking.PARENT);
        assertThat(untracked.stockTracking()).isEqualTo(StockTracking.NONE);
        assertThat(latest(scope, tracked).reading().availableToSell()).isEqualTo(18);
        assertThat(latest(scope, parent).reading().stockQuantity()).isEqualTo(7);
        assertThat(latest(scope, untracked).reading().stockQuantity()).isNull();
        assertThat(latest(scope, untracked).reading().stockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);

        Instant from = run.windowFrom();
        assertThat(orders.unitsSold(scope, tracked.id(), from, run.windowUntil(), List.of("processing"))).isEqualTo(2);
        assertThat(orders.unitsSold(scope, variant(scope, "20").id(), from, run.windowUntil(), List.of("completed")))
                .isEqualTo(SIMPLE_ORDERS);
        assertThat(orders.coverage(scope, CommerceSource.WOOCOMMERCE))
                .contains(new OrderCoverage(CommerceSource.WOOCOMMERCE, run.windowFrom(), run.windowUntil()));

        // Customer data is never requested; paging used the documented filters.
        List<String> orderRequests = requests.stream().filter(r -> r.startsWith("/wp-json/wc/v3/orders")).toList();
        assertThat(orderRequests).hasSize(2).allSatisfy(request -> assertThat(request)
                .contains("_fields=id%2Cstatus%2Ccurrency").doesNotContain("billing").doesNotContain("customer")
                .contains("dates_are_gmt=true").contains("status=any"));
        assertThat(requests.stream().filter(r -> r.startsWith("/wp-json/wc/v3/products?"))).hasSize(2);

        // Started by the owner, executed by the import service.
        assertThat(jdbc.sql("""
                        SELECT coalesce(actor_user_id::text, actor_service) FROM audit_event
                        WHERE store_id = ? AND action IN ('INTEGRATION_SYNC_STARTED', 'INTEGRATION_SYNC_FINISHED')
                          AND resource_id = ? ORDER BY occurred_at
                        """).params(STORE, run.id().toString()).query(String.class).list())
                .containsExactly(OWNER.toString(), StoreSync.IMPORT_SERVICE);
    }

    @Test
    void reimportIsIdempotentAndAppendsStockObservations() {
        UUID integration = connectedIntegration();
        importAndWait(integration);
        SyncRun second = importAndWait(integration);

        assertThat(second.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        TrustedStoreScope scope = owner();
        assertThat(catalog.productCount(scope)).isEqualTo(2 + EXTRA_PRODUCTS);
        assertThat(catalog.variants(scope)).hasSize(4 + EXTRA_PRODUCTS);
        assertThat(orders.orderCount(scope, CommerceSource.WOOCOMMERCE, second.windowFrom()))
                .isEqualTo(SIMPLE_ORDERS + 1);
        Variant tracked = variant(scope, "11");
        assertThat(inventory.observed(scope, tracked.id(), Instant.EPOCH, clock.instant().plusSeconds(1))).hasSize(2);
        // Nothing reached the other store of the same owner.
        TrustedStoreScope other = storeAccess.scopeFor(OWNER, OTHER_STORE).orElseThrow();
        assertThat(catalog.productCount(other)).isZero();
    }

    @Test
    void countMismatchLeavesThePeriodIncomplete() {
        reportedOrderTotal = SIMPLE_ORDERS + 5;

        SyncRun run = importAndWait(connectedIntegration());

        assertThat(run.status()).isEqualTo(SyncRunStatus.INCOMPLETE);
        assertThat(run.errorCode()).isEqualTo("ORDER_COUNT_MISMATCH");
        assertThat(orders.coverage(owner(), CommerceSource.WOOCOMMERCE)).isEmpty();
    }

    @Test
    void rejectedKeyFailsTheRunWithoutRetrying() {
        UUID integration = connectedIntegration();
        rejectKey = true;

        SyncRun run = importAndWait(integration);

        assertThat(run.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(run.errorCode()).isEqualTo("AUTH_FAILED");
        assertThat(run.finishedAt()).isNotNull();
        assertThat(requests).hasSize(1);
    }

    @Test
    void onlyOneImportRunsAndOnlyManagersStartIt() {
        UUID integration = connectedIntegration();
        TrustedStoreScope analyst = storeAccess.scopeFor(ANALYST, STORE).orElseThrow();
        assertThatThrownBy(() -> sync.startInitialImport(analyst, integration))
                .isInstanceOf(StoreAccessDeniedException.class);

        jdbc.sql("""
                INSERT INTO sync_run (id, organization_id, store_id, connection_id, kind, status, window_from,
                                      window_until, started_at)
                VALUES (?, ?, ?, ?, 'INITIAL_IMPORT', 'RUNNING', now() - interval '1 day', now(), now())
                """).params(UUID.randomUUID(), ORG, STORE, integration).update();
        assertThatThrownBy(() -> sync.startInitialImport(owner(), integration))
                .isInstanceOf(SyncInProgressException.class);
    }

    private Variant variant(TrustedStoreScope scope, String externalId) {
        return catalog.variantByExternalId(scope, CommerceSource.WOOCOMMERCE, externalId).orElseThrow();
    }

    private InventoryObservation latest(TrustedStoreScope scope, Variant variant) {
        Optional<InventoryObservation> latest = inventory.latest(scope, variant.id());
        return latest.orElseThrow();
    }

    // --- stub WooCommerce -------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().toString();
        requests.add(uri);
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        if (rejectKey || !KEY.equals(query.get("oauth_consumer_key"))) {
            respond(exchange, 401, "{\"code\":\"woocommerce_rest_authentication_error\"}", null);
            return;
        }
        String path = exchange.getRequestURI().getPath().substring("/wp-json/wc/v3/".length());
        int page = Integer.parseInt(query.getOrDefault("page", "1"));
        int perPage = Integer.parseInt(query.getOrDefault("per_page", "10"));
        switch (path) {
            case "products" -> page(exchange, products(), page, perPage, null);
            case "products/10/variations" -> page(exchange, variations(), page, perPage, null);
            case "orders" -> page(exchange, orderList(), page, perPage, reportedOrderTotal);
            case "settings/general/woocommerce_currency" -> respond(exchange, 200, "{\"value\":\"PLN\"}", null);
            default -> respond(exchange, 404, "{\"code\":\"rest_no_route\"}", null);
        }
    }

    private List<String> products() {
        List<String> items = new ArrayList<>();
        items.add("""
                {"id":10,"name":"Koszulka","sku":"TSHIRT","type":"variable","status":"publish",
                 "date_modified_gmt":"2026-11-01T10:00:00","manage_stock":true,"stock_quantity":7,
                 "stock_status":"instock","backorders":"no"}""");
        items.add("""
                {"id":20,"name":"Kubek","sku":"MUG","type":"simple","status":"publish",
                 "date_modified_gmt":"2026-11-01T10:00:00","manage_stock":true,"stock_quantity":49,
                 "stock_status":"instock","backorders":"no"}""");
        IntStream.range(0, EXTRA_PRODUCTS).forEach(i -> items.add("""
                {"id":%d,"name":"Extra %d","sku":"","type":"simple","status":"publish",
                 "date_modified_gmt":null,"manage_stock":false,"stock_quantity":null,
                 "stock_status":"instock","backorders":"no"}""".formatted(1000 + i, i)));
        return items;
    }

    private static List<String> variations() {
        return List.of("""
                {"id":11,"sku":"TSHIRT-S","status":"publish","date_modified_gmt":"2026-11-01T10:00:00",
                 "manage_stock":true,"stock_quantity":18,"stock_status":"instock","backorders":"no",
                 "attributes":[{"name":"Rozmiar","option":"S"}]}""", """
                {"id":12,"sku":"TSHIRT-M","status":"publish","date_modified_gmt":"2026-11-01T10:00:00",
                 "manage_stock":"parent","stock_quantity":null,"stock_status":"instock","backorders":"no",
                 "attributes":[{"name":"Rozmiar","option":"M"}]}""", """
                {"id":13,"sku":"TSHIRT-L","status":"publish","date_modified_gmt":"2026-11-01T10:00:00",
                 "manage_stock":false,"stock_quantity":null,"stock_status":"outofstock","backorders":"no",
                 "attributes":[{"name":"Rozmiar","option":"L"}]}""");
    }

    private List<String> orderList() {
        Instant base = clock.instant().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        List<String> items = new ArrayList<>();
        items.add(order(5000, "processing", base, "[{\"id\":1,\"product_id\":10,\"variation_id\":11,\"sku\":\"TSHIRT-S\","
                + "\"quantity\":2,\"total\":\"158.00\",\"total_tax\":\"29.54\"}]"));
        IntStream.range(0, SIMPLE_ORDERS).forEach(i -> items.add(order(6000 + i, "completed", base.plusSeconds(60L * i),
                "[{\"id\":" + (100 + i) + ",\"product_id\":20,\"variation_id\":0,\"sku\":\"MUG\",\"quantity\":1,"
                        + "\"total\":\"39.00\",\"total_tax\":\"7.29\"}]")));
        return items;
    }

    private static String order(int id, String status, Instant created, String lines) {
        String date = LocalDateTime.ofInstant(created, ZoneOffset.UTC).toString();
        return """
                {"id":%d,"status":"%s","currency":"PLN","total":"100.00","total_tax":"18.70","discount_total":"0.00",
                 "shipping_total":"0.00","date_created_gmt":"%s","date_paid_gmt":"%s","date_completed_gmt":null,
                 "date_modified_gmt":"%s","line_items":%s}""".formatted(id, status, date, date, date, lines);
    }

    private static void page(HttpExchange exchange, List<String> items, int page, int perPage, Integer reportedTotal)
            throws IOException {
        int from = Math.min((page - 1) * perPage, items.size());
        int to = Math.min(from + perPage, items.size());
        String body = items.subList(from, to).stream().collect(Collectors.joining(",", "[", "]"));
        respond(exchange, 200, body, reportedTotal == null ? items.size() : reportedTotal);
    }

    private static void respond(HttpExchange exchange, int status, String body, Integer total) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        if (total != null) {
            exchange.getResponseHeaders().set("X-WP-Total", total.toString());
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new java.util.HashMap<>();
        if (raw == null) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            result.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return result;
    }
}
