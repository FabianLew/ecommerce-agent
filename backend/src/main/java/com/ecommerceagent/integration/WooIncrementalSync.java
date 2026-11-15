package com.ecommerceagent.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.OrderRepository;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.WooReader.Source;
import com.ecommerceagent.integration.woocommerce.WooResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Incremental WooCommerce sync (B-16, ADR-0010). Polling reads products and variations changed since the
 * products watermark; reconciliation reads orders changed since the orders watermark and extends the order
 * coverage only when the count matches the source. Both detect changes a configured webhook should have
 * delivered.
 */
@Service
public class WooIncrementalSync {

    /** Overlap of each read with the previous one: source clocks and second precision are not exact. */
    static final Duration OVERLAP = Duration.ofMinutes(2);
    /** A change older than this should have arrived by webhook. */
    static final Duration WEBHOOK_GRACE = Duration.ofMinutes(2);
    static final int PAGE_SIZE = 100;
    private static final CommerceSource SOURCE = CommerceSource.WOOCOMMERCE;
    private static final Logger log = LoggerFactory.getLogger(WooIncrementalSync.class);

    private final WooReader reader;
    private final WooPayloadNormalizer normalizer;
    private final SyncStateRepository state;
    private final CatalogRepository catalog;
    private final OrderRepository orders;
    private final TransactionTemplate transactions;
    private final Clock clock;

    WooIncrementalSync(WooReader reader, WooPayloadNormalizer normalizer, SyncStateRepository state,
            CatalogRepository catalog, OrderRepository orders, TransactionTemplate transactions, Clock clock) {
        this.reader = reader;
        this.normalizer = normalizer;
        this.state = state;
        this.catalog = catalog;
        this.orders = orders;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Connections of the store that need schedules: connected and imported (worker side). */
    public List<SyncTarget> targets(TrustedStoreScope scope) {
        return state.syncTargets(scope);
    }

    /** Reads products and variations changed since the watermark and records them as fresh reads. */
    public SyncCheck poll(TrustedStoreScope scope, UUID connectionId) {
        Instant startedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Optional<Instant> watermark = state.watermark(scope, connectionId, SyncStream.PRODUCTS)
                .or(() -> state.lastSuccessfulImport(scope, connectionId).map(SyncRun::startedAt));
        if (watermark.isEmpty()) {
            return record(scope, connectionId, failed(SyncCheck.Kind.POLL, startedAt, "NOT_READY"));
        }
        try {
            Source source = reader.source(scope);
            requireConnection(source, connectionId);
            String after = WooPayloads.wooDate(watermark.get().minus(OVERLAP));
            Counter counter = new Counter(source.connection(), startedAt);
            Instant newest = watermark.get();

            for (JsonNode product : pages(source, "products", Map.of("modified_after", after, "dates_are_gmt", "true",
                    "orderby", "id", "order", "asc", "status", "any",
                    "_fields", WooSourceRefresh.PRODUCT_FIELDS))) {
                newest = max(newest, WooPayloads.gmt(product, "date_modified_gmt"));
                apply(scope, product, counter);
            }
            // A variation change does not touch its parent's modification time, so variations are read per product.
            for (String productId : catalog.productExternalIdsOfType(scope, SOURCE, "variable")) {
                for (JsonNode variation : pages(source, "products/" + productId + "/variations", Map.of(
                        "modified_after", after, "dates_are_gmt", "true", "orderby", "id", "order", "asc",
                        "_fields", WooSourceRefresh.PRODUCT_FIELDS))) {
                    newest = max(newest, WooPayloads.gmt(variation, "date_modified_gmt"));
                    apply(scope, WooReader.asVariation(variation, productId, WooPayloads.variationName(
                            catalogName(scope, productId), variation)), counter);
                }
            }
            state.advance(scope, connectionId, SyncStream.PRODUCTS, newest);
            return record(scope, connectionId, new SyncCheck(SyncCheck.Kind.POLL, SyncCheck.Status.SUCCEEDED,
                    startedAt, clock.instant(), counter.changes, counter.missed, null));
        } catch (WooImportException e) {
            log.warn("Poll of connection {} failed: {}", connectionId, e.getMessage());
            return record(scope, connectionId, failed(SyncCheck.Kind.POLL, startedAt, e.error().name()));
        }
    }

    /**
     * Reads every order changed since the watermark. An order placed before the start of this run is modified no
     * earlier than it was placed, so a complete read (count equal to {@code X-WP-Total}) covers all orders placed
     * up to the start: the coverage extends there and the watermark moves there. Otherwise nothing moves.
     * There is no upper bound on the modification time: an order placed before the start and changed during the
     * run would fall outside it and leave a hole in the coverage.
     */
    public SyncCheck reconcileOrders(TrustedStoreScope scope, UUID connectionId) {
        Instant startedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Optional<Instant> watermark = state.watermark(scope, connectionId, SyncStream.ORDERS)
                .or(() -> state.lastSuccessfulImport(scope, connectionId).map(SyncRun::windowUntil));
        if (watermark.isEmpty()) {
            return record(scope, connectionId, failed(SyncCheck.Kind.RECONCILE, startedAt, "NOT_READY"));
        }
        try {
            Source source = reader.source(scope);
            requireConnection(source, connectionId);
            Instant from = watermark.get().minus(OVERLAP);
            Map<String, String> query = new LinkedHashMap<>();
            query.put("modified_after", WooPayloads.wooDate(from));
            query.put("dates_are_gmt", "true");
            query.put("orderby", "id");
            query.put("order", "asc");
            query.put("status", "any");
            query.put("_fields", WooCommerceImport.ORDER_FIELDS);
            Counter counter = new Counter(source.connection(), startedAt);
            long expected = -1;
            int read = 0;
            for (int page = 1; ; page++) {
                query.put("per_page", Integer.toString(PAGE_SIZE));
                query.put("page", Integer.toString(page));
                WooResponse response = reader.response(source, "orders", query);
                if (response == null) {
                    throw new WooImportException(ConnectionCheckError.NOT_WOOCOMMERCE, "Orders endpoint missing");
                }
                if (expected < 0) {
                    expected = response.total().orElse(-1);
                }
                JsonNode items = WooReader.body(response);
                for (JsonNode order : items) {
                    read++;
                    applyOrder(scope, WooPayloads.minimizeOrder(order), counter);
                }
                if (items.size() < PAGE_SIZE) {
                    break;
                }
            }
            if (expected != read) {
                return record(scope, connectionId, new SyncCheck(SyncCheck.Kind.RECONCILE,
                        SyncCheck.Status.INCOMPLETE, startedAt, clock.instant(), counter.changes, counter.missed,
                        "ORDER_COUNT_MISMATCH"));
            }
            transactions.executeWithoutResult(status -> {
                orders.recordCoverage(scope, SOURCE, from, startedAt);
                state.advance(scope, connectionId, SyncStream.ORDERS, startedAt);
            });
            return record(scope, connectionId, new SyncCheck(SyncCheck.Kind.RECONCILE, SyncCheck.Status.SUCCEEDED,
                    startedAt, clock.instant(), counter.changes, counter.missed, null));
        } catch (WooImportException e) {
            log.warn("Order reconciliation of connection {} failed: {}", connectionId, e.getMessage());
            return record(scope, connectionId, failed(SyncCheck.Kind.RECONCILE, startedAt, e.error().name()));
        }
    }

    private void apply(TrustedStoreScope scope, JsonNode product, Counter counter) {
        String readId = "poll:" + UUID.randomUUID();
        String outcome = transactions.execute(status -> normalizer.applyFresh(scope, readId, product).outcome());
        long recorded = outcome == null ? 0 : java.util.Arrays.stream(outcome.split(","))
                .filter("RECORDED"::equals).count();
        if (recorded > 0) {
            counter.changes += (int) recorded;
            if (counter.webhookShouldHaveArrived(WooPayloads.gmt(product, "date_modified_gmt"))) {
                counter.missed += (int) recorded;
            }
        }
    }

    private void applyOrder(TrustedStoreScope scope, JsonNode order, Counter counter) {
        String externalId = WooPayloads.text(order, "id");
        Instant modified = WooPayloads.gmt(order, "date_modified_gmt");
        Optional<Instant> known = orders.sourceUpdatedAt(scope, SOURCE, externalId);
        boolean news = known.isEmpty() || (modified != null && modified.isAfter(known.get()));
        transactions.executeWithoutResult(status -> normalizer.applyOrder(scope, order));
        if (news) {
            counter.changes++;
            if (counter.webhookShouldHaveArrived(modified)) {
                counter.missed++;
            }
        }
    }

    private List<JsonNode> pages(Source source, String resource, Map<String, String> filters) {
        List<JsonNode> all = new ArrayList<>();
        for (int page = 1; ; page++) {
            Map<String, String> query = new LinkedHashMap<>(filters);
            query.put("per_page", Integer.toString(PAGE_SIZE));
            query.put("page", Integer.toString(page));
            JsonNode items = reader.json(source, resource, query);
            if (items == null || !items.isArray()) {
                return all;
            }
            items.forEach(all::add);
            if (items.size() < PAGE_SIZE) {
                return all;
            }
        }
    }

    private String catalogName(TrustedStoreScope scope, String productId) {
        return catalog.variantsOfProduct(scope, catalog.productIdByExternalId(scope, SOURCE, productId).orElseThrow())
                .stream().findFirst().map(variant -> variant.productName()).orElse("");
    }

    private static void requireConnection(Source source, UUID connectionId) {
        if (!source.connection().id().equals(connectionId)) {
            throw new WooImportException(ConnectionCheckError.AUTH_FAILED, "Connection was replaced");
        }
    }

    private SyncCheck record(TrustedStoreScope scope, UUID connectionId, SyncCheck check) {
        state.recordCheck(scope, connectionId, check);
        return check;
    }

    private SyncCheck failed(SyncCheck.Kind kind, Instant startedAt, String code) {
        return new SyncCheck(kind, SyncCheck.Status.FAILED, startedAt, clock.instant(), 0, 0, code);
    }

    private static Instant max(Instant a, Instant b) {
        return b != null && b.isAfter(a) ? b : a;
    }

    /** Counts changes, and those a webhook configured before the change should have delivered by now. */
    private static final class Counter {
        private final Instant secretSetAt;
        private final Instant cutoff;
        int changes;
        int missed;

        Counter(IntegrationConnection connection, Instant startedAt) {
            this.secretSetAt = connection.webhookSecretSetAt();
            this.cutoff = startedAt.minus(WEBHOOK_GRACE);
        }

        boolean webhookShouldHaveArrived(Instant modified) {
            return secretSetAt != null && modified != null && modified.isAfter(secretSetAt) && modified.isBefore(cutoff);
        }
    }
}
