package com.ecommerceagent.integration;

import static com.ecommerceagent.integration.WooPayloads.backorders;
import static com.ecommerceagent.integration.WooPayloads.emptyToNull;
import static com.ecommerceagent.integration.WooPayloads.gmt;
import static com.ecommerceagent.integration.WooPayloads.stockStatus;
import static com.ecommerceagent.integration.WooPayloads.text;
import static com.ecommerceagent.integration.WooPayloads.variationName;
import static com.ecommerceagent.integration.WooPayloads.wooDate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.InventoryReading;
import com.ecommerceagent.commerce.InventoryRepository;
import com.ecommerceagent.commerce.ObservationSource;
import com.ecommerceagent.commerce.OrderRepository;
import com.ecommerceagent.commerce.ProductData;
import com.ecommerceagent.commerce.ReservationSemantics;
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

    /** Confirms that the run's workflow has started (FIX-02); repeated calls change nothing. */
    public void markWorkflowStarted(TrustedStoreScope scope, UUID runId) {
        runs.markWorkflowStarted(scope, runId);
    }

    /**
     * Running imports of all stores, for the system reconciler only (FIX-02). Identifiers only; the
     * reconciler builds a system scope for each run's store before touching it.
     */
    public List<SyncRunDispatch> runningRuns(int limit) {
        return runs.running(limit);
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
                orders.upsert(scope, CommerceSource.WOOCOMMERCE, WooPayloads.order(order));
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
        StockTracking tracking = WooPayloads.productTracking(product);
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
                StockTracking tracking = WooPayloads.variationTracking(variation);
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
        Integer stock = WooPayloads.stockQuantity(stockSource, tracking);
        // Woo stock already includes on-hold/processing/completed orders (ADR-0008 experiment).
        inventory.record(scope, variantId, new InventoryReading(stock, stockStatus(stockSource),
                ReservationSemantics.ALREADY_ACCOUNTED_FOR, null, ObservationSource.IMPORT,
                gmt(stockSource, "date_modified_gmt"), observedAt));
    }

    /**
     * Steps work only on a running run: a run closed by the reconciler (e.g. its start expired) never
     * imports, even if a late workflow start reaches it (FIX-02).
     */
    private Source source(TrustedStoreScope scope, UUID runId) {
        SyncRun run = run(scope, runId);
        if (run.status() != SyncRunStatus.RUNNING) {
            throw new InvalidIntegrationRequestException("run", "RUN_CLOSED", "The import run has already ended");
        }
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
                case UNREACHABLE -> ConnectionCheckError.UNREACHABLE;
                case TLS_ERROR -> ConnectionCheckError.TLS_ERROR;
                case RESPONSE_TOO_LARGE -> ConnectionCheckError.UNEXPECTED_RESPONSE;
            }, e.getMessage());
        }
    }

    private static JsonNode array(WooResponse response) {
        return JSON.readTree(response.body());
    }

    private record Source(IntegrationConnection connection, WooCredentials credentials) {
    }

    public record CatalogCounts(int products, int variants) {
    }

    public record OrderCounts(int imported, int expected, boolean complete) {
    }
}
