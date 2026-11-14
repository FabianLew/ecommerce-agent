package com.ecommerceagent.integration;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.woocommerce.WooClientException;
import com.ecommerceagent.integration.woocommerce.WooCommerceClient;
import com.ecommerceagent.integration.woocommerce.WooCredentials;
import com.ecommerceagent.integration.woocommerce.WooResponse;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Initial WooCommerce import (B-13, ADR-0008): catalog with current stock, and orders of the minimal window.
 * Steps are idempotent, so a retried activity repeats a step without duplicates. Orders are fetched with a
 * field list that excludes customer data.
 */
@Service
public class WooCommerceImport {

    /** Days before today (store time) included in the initial window; full history comes in B-13b. */
    public static final int INITIAL_WINDOW_DAYS = 14;

    static final int PAGE_SIZE = 100;
    static final String PRODUCT_FIELDS = "id,name,sku,type,status,date_modified_gmt,manage_stock,stock_quantity,"
            + "stock_status,backorders";
    static final String VARIATION_FIELDS = "id,sku,status,date_modified_gmt,manage_stock,stock_quantity,stock_status,"
            + "backorders,attributes";
    /** No billing, shipping, customer or note fields: customer data stays in WooCommerce (ADR-0008). */
    static final String ORDER_FIELDS = "id,status,currency,total,total_tax,discount_total,shipping_total,"
            + "date_created_gmt,date_paid_gmt,date_completed_gmt,date_modified_gmt,line_items";
    private static final DateTimeFormatter WOO_DATE = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final IntegrationConnections connections;
    private final IntegrationConnectionRepository connectionRepository;
    private final SyncRunRepository runs;
    private final WooCommerceClient client;
    private final CatalogRepository catalog;
    private final InventoryRepository inventory;
    private final OrderRepository orders;
    private final AuditTrail audit;
    private final Clock clock;

    WooCommerceImport(IntegrationConnections connections, IntegrationConnectionRepository connectionRepository,
            SyncRunRepository runs, WooCommerceClient client, CatalogRepository catalog, InventoryRepository inventory,
            OrderRepository orders, AuditTrail audit, Clock clock) {
        this.connections = connections;
        this.connectionRepository = connectionRepository;
        this.runs = runs;
        this.client = client;
        this.catalog = catalog;
        this.inventory = inventory;
        this.orders = orders;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Registers an initial import requested by a user who manages the store. The window starts at local
     * midnight {@value #INITIAL_WINDOW_DAYS} days ago and ends now.
     */
    public SyncRun createInitialImport(TrustedStoreScope scope, UUID connectionId) {
        scope.require(StorePermission.STORE_MANAGE);
        IntegrationConnection connection = connections.get(scope, connectionId);
        if (connection.status() != ConnectionStatus.CONNECTED) {
            throw new InvalidIntegrationRequestException("integration", "NOT_CONNECTED",
                    "Run a successful connection check before importing");
        }
        // Whole seconds: WooCommerce date filters have second precision.
        Instant until = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        ZoneId zone = ZoneId.of(connectionRepository.storeTimezone(scope));
        Instant from = LocalDate.ofInstant(until, zone).minusDays(INITIAL_WINDOW_DAYS).atStartOfDay(zone).toInstant();
        SyncRun run = runs.create(scope, connectionId, scope.userId().orElse(null), from, until);
        audit.record(AuditEntry.inScope(scope, "INTEGRATION_SYNC_STARTED", "sync_run", run.id().toString(),
                Map.of("connectionId", connectionId.toString(), "kind", "INITIAL_IMPORT")));
        return run;
    }

    public SyncRun run(TrustedStoreScope scope, UUID runId) {
        return runs.find(scope, runId).orElseThrow(() -> new IntegrationNotFoundException(runId));
    }

    public java.util.Optional<SyncRun> latestRun(TrustedStoreScope scope, UUID connectionId) {
        return runs.latest(scope, connectionId);
    }

    /** Imports all products and variants with their current stock. */
    public CatalogCounts importCatalog(TrustedStoreScope scope, UUID runId, Runnable heartbeat) {
        Source source = source(scope, runId);
        Instant observedAt = clock.instant();
        int products = 0;
        int variants = 0;
        for (int page = 1; ; page++) {
            JsonNode items = list(source, "products", Map.of("per_page", Integer.toString(PAGE_SIZE),
                    "page", Integer.toString(page), "orderby", "id", "order", "asc", "status", "any",
                    "_fields", PRODUCT_FIELDS));
            for (JsonNode product : items) {
                products++;
                variants += importProduct(scope, source, product, observedAt);
            }
            heartbeat.run();
            if (items.size() < PAGE_SIZE) {
                break;
            }
        }
        runs.recordCatalog(scope, runId, products, variants);
        return new CatalogCounts(products, variants);
    }

    /**
     * Imports orders of the run's window. The period is marked complete only if the number of imported
     * orders matches the total reported by WooCommerce (ADR-0008).
     */
    public OrderCounts importOrders(TrustedStoreScope scope, UUID runId, Runnable heartbeat) {
        Source source = source(scope, runId);
        SyncRun run = run(scope, runId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("per_page", Integer.toString(PAGE_SIZE));
        query.put("after", wooDate(run.windowFrom().minusSeconds(1)));
        query.put("before", wooDate(run.windowUntil()));
        query.put("dates_are_gmt", "true");
        query.put("orderby", "date");
        query.put("order", "asc");
        query.put("status", "any");
        query.put("_fields", ORDER_FIELDS);

        int imported = 0;
        long expected = -1;
        for (int page = 1; ; page++) {
            query.put("page", Integer.toString(page));
            WooResponse response = get(source, "orders", query);
            if (expected < 0) {
                expected = response.total().orElse(-1);
            }
            JsonNode items = array(response);
            for (JsonNode order : items) {
                // "after" is exclusive, so the query starts one second early; an extra order is harmless.
                orders.upsert(scope, CommerceSource.WOOCOMMERCE, order(order));
                imported++;
            }
            heartbeat.run();
            if (items.size() < PAGE_SIZE) {
                break;
            }
        }
        runs.recordOrders(scope, runId, imported, (int) expected);
        boolean complete = expected == imported;
        if (complete) {
            orders.recordCoverage(scope, CommerceSource.WOOCOMMERCE, run.windowFrom(), run.windowUntil());
        }
        return new OrderCounts(imported, (int) expected, complete);
    }

    /** Ends the run once; later calls (e.g. a replayed workflow step) change nothing. */
    public void finish(TrustedStoreScope scope, UUID runId, SyncRunStatus status, String errorCode) {
        if (status == SyncRunStatus.RUNNING) {
            throw new IllegalArgumentException("A run cannot finish as RUNNING");
        }
        if (runs.finish(scope, runId, status, errorCode)) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("status", status.name());
            details.put("errorCode", errorCode == null ? "" : errorCode);
            audit.record(AuditEntry.inScope(scope, "INTEGRATION_SYNC_FINISHED", "sync_run", runId.toString(), details));
        }
    }

    private int importProduct(TrustedStoreScope scope, Source source, JsonNode product, Instant observedAt) {
        String productId = text(product, "id");
        String name = text(product, "name");
        String type = text(product, "type");
        catalog.upsertProduct(scope, CommerceSource.WOOCOMMERCE, new ProductData(productId, name == null ? "" : name,
                emptyToNull(text(product, "sku")), type == null ? "unknown" : type, text(product, "status"),
                gmt(product, "date_modified_gmt")));
        if ("variable".equals(type)) {
            return importVariations(scope, source, product, observedAt);
        }
        // A simple (or other non-variable) product is its own sellable variant.
        StockTracking tracking = product.path("manage_stock").asBoolean(false) ? StockTracking.VARIANT : StockTracking.NONE;
        recordVariant(scope, new VariantData(productId, productId, name == null ? "" : name,
                emptyToNull(text(product, "sku")), text(product, "status"), tracking, backorders(product),
                gmt(product, "date_modified_gmt")), product, tracking, observedAt);
        return 1;
    }

    private int importVariations(TrustedStoreScope scope, Source source, JsonNode product, Instant observedAt) {
        String productId = text(product, "id");
        int count = 0;
        for (int page = 1; ; page++) {
            JsonNode items = list(source, "products/" + productId + "/variations",
                    Map.of("per_page", Integer.toString(PAGE_SIZE), "page", Integer.toString(page), "orderby", "id",
                            "order", "asc", "_fields", VARIATION_FIELDS));
            for (JsonNode variation : items) {
                JsonNode manage = variation.path("manage_stock");
                StockTracking tracking = manage.isBoolean()
                        ? (manage.asBoolean() ? StockTracking.VARIANT : StockTracking.NONE)
                        : ("parent".equals(manage.asString(null)) ? StockTracking.PARENT : StockTracking.NONE);
                JsonNode stockSource = tracking == StockTracking.PARENT ? product : variation;
                recordVariant(scope, new VariantData(text(variation, "id"), productId,
                        variationName(text(product, "name"), variation), emptyToNull(text(variation, "sku")),
                        text(variation, "status"), tracking, backorders(variation), gmt(variation, "date_modified_gmt")),
                        stockSource, tracking, observedAt);
                count++;
            }
            if (items.size() < PAGE_SIZE) {
                return count;
            }
        }
    }

    private void recordVariant(TrustedStoreScope scope, VariantData variant, JsonNode stockSource,
            StockTracking tracking, Instant observedAt) {
        UUID variantId = catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, variant);
        JsonNode quantity = stockSource.path("stock_quantity");
        Integer stock = tracking != StockTracking.NONE && quantity.isNumber() ? quantity.asInt() : null;
        // Woo stock already includes on-hold/processing/completed orders (ADR-0008 experiment).
        inventory.record(scope, variantId, new InventoryReading(stock, stockStatus(stockSource),
                ReservationSemantics.ALREADY_ACCOUNTED_FOR, null, ObservationSource.IMPORT,
                gmt(stockSource, "date_modified_gmt"), observedAt));
    }

    private OrderData order(JsonNode order) {
        List<OrderLineData> lines = new ArrayList<>();
        for (JsonNode line : order.path("line_items")) {
            String productId = text(line, "product_id");
            String variationId = text(line, "variation_id");
            String variantId = variationId == null || "0".equals(variationId) ? productId : variationId;
            lines.add(new OrderLineData(text(line, "id"), "0".equals(variantId) ? null : variantId,
                    "0".equals(productId) ? null : productId, emptyToNull(text(line, "sku")),
                    line.path("quantity").asInt(0), money(line, "total"), money(line, "total_tax")));
        }
        Instant placedAt = gmt(order, "date_created_gmt");
        if (placedAt == null) {
            throw new WooImportException(ConnectionCheckError.UNEXPECTED_RESPONSE, "Order without creation date");
        }
        return new OrderData(text(order, "id"), text(order, "status"), text(order, "currency"), money(order, "total"),
                money(order, "total_tax"), money(order, "discount_total"), money(order, "shipping_total"), placedAt,
                gmt(order, "date_paid_gmt"), gmt(order, "date_completed_gmt"), gmt(order, "date_modified_gmt"),
                lines);
    }

    private Source source(TrustedStoreScope scope, UUID runId) {
        SyncRun run = run(scope, runId);
        IntegrationConnection connection = connections.get(scope, run.connectionId());
        return new Source(connection, connections.wooCredentials(scope, connection));
    }

    private JsonNode list(Source source, String resource, Map<String, String> query) {
        return array(get(source, resource, query));
    }

    private WooResponse get(Source source, String resource, Map<String, String> query) {
        try {
            WooResponse response = client.get(source.connection().storeUrl(), source.credentials(), resource, query);
            ConnectionCheckError error = WooConnectionChecker.classifyList(response);
            if (error != null) {
                throw new WooImportException(error, "GET " + resource + " returned " + response.status());
            }
            return response;
        } catch (WooClientException e) {
            throw new WooImportException(switch (e.kind()) {
                case ADDRESS_NOT_ALLOWED -> ConnectionCheckError.ADDRESS_NOT_ALLOWED;
                case UNREACHABLE, TIMEOUT -> ConnectionCheckError.UNREACHABLE;
                case TLS_ERROR -> ConnectionCheckError.TLS_ERROR;
                case RESPONSE_TOO_LARGE -> ConnectionCheckError.UNEXPECTED_RESPONSE;
            }, e.getMessage());
        }
    }

    private static JsonNode array(WooResponse response) {
        return JSON.readTree(response.body());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.isString() ? value.asString() : value.toString();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** WooCommerce *_gmt fields are UTC without an offset, e.g. {@code 2026-11-14T10:00:00}. */
    static Instant gmt(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, WOO_DATE).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new WooImportException(ConnectionCheckError.UNEXPECTED_RESPONSE, "Unreadable date in " + field);
        }
    }

    static String wooDate(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).withNano(0).format(WOO_DATE);
    }

    private static BigDecimal money(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new WooImportException(ConnectionCheckError.UNEXPECTED_RESPONSE, "Unreadable amount in " + field);
        }
    }

    private static StockStatus stockStatus(JsonNode node) {
        return switch (String.valueOf(text(node, "stock_status"))) {
            case "outofstock" -> StockStatus.OUT_OF_STOCK;
            case "onbackorder" -> StockStatus.ON_BACKORDER;
            default -> StockStatus.IN_STOCK;
        };
    }

    private static Backorders backorders(JsonNode node) {
        return switch (String.valueOf(text(node, "backorders"))) {
            case "notify" -> Backorders.NOTIFY;
            case "yes" -> Backorders.YES;
            default -> Backorders.NO;
        };
    }

    private static String variationName(String productName, JsonNode variation) {
        List<String> options = new ArrayList<>();
        for (JsonNode attribute : variation.path("attributes")) {
            String option = text(attribute, "option");
            if (option != null) {
                options.add(option);
            }
        }
        String base = productName == null ? "" : productName;
        return options.isEmpty() ? base : base + " – " + String.join(", ", options);
    }

    private record Source(IntegrationConnection connection, WooCredentials credentials) {
    }

    public record CatalogCounts(int products, int variants) {
    }

    public record OrderCounts(int imported, int expected, boolean complete) {
    }
}
