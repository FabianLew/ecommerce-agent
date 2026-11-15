package com.ecommerceagent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.commerce.Backorders;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.InventoryReading;
import com.ecommerceagent.commerce.InventoryRepository;
import com.ecommerceagent.commerce.ObservationSource;
import com.ecommerceagent.commerce.ProductData;
import com.ecommerceagent.commerce.ReservationSemantics;
import com.ecommerceagent.commerce.StockStatus;
import com.ecommerceagent.commerce.StockTracking;
import com.ecommerceagent.commerce.VariantData;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.WebhookInboxRepository.InboxEntry;
import com.ecommerceagent.integration.WebhookInboxRepository.InboxStatus;
import com.ecommerceagent.integration.WooWebhooks.WebhookHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Webhook normalization (B-14, ADR-0009) and the golden scenario IG-04: an old event after a new one, or a
 * repeated delivery, never regresses stock and produces no second domain event.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class WebhookNormalizationTest {

    static final UUID ORG = UUID.fromString("51000000-0000-4000-8000-000000000001");
    static final UUID STORE = UUID.fromString("61000000-0000-4000-8000-00000000000a");
    static final UUID OWNER = UUID.fromString("71000000-0000-4000-8000-0000000000a1");
    static final UUID CONNECTION = UUID.fromString("81000000-0000-4000-8000-0000000000c1");
    static final String KEY = "0123456789abcdef0123456789abcdef";
    static final String SECRET = "whsec-normalization-0123456789";
    static final String T0 = "2026-11-14T08:00:00";
    static final String T1 = "2026-11-14T09:00:00";
    static final String T2 = "2026-11-14T10:00:00";

    @Autowired
    WooWebhooks webhooks;

    @Autowired
    WooPayloadNormalizer normalizer;

    @Autowired
    WebhookInboxRepository inbox;

    @Autowired
    CatalogRepository catalog;

    @Autowired
    InventoryRepository inventory;

    @Autowired
    StoreAccess storeAccess;

    @Autowired
    FixtureDb jdbc;

    TrustedStoreScope scope;
    UUID mug;
    UUID shirtS;
    UUID shirtM;
    UUID shirtL;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Org')").params(ORG).update();
        jdbc.sql("INSERT INTO store (id, organization_id, name, currency, timezone) VALUES (?, ?, 'S', 'PLN', 'UTC')")
                .params(STORE, ORG).update();
        jdbc.sql("INSERT INTO app_user (id, issuer, subject, email) VALUES (?, 'https://issuer.test', 'o', 'o@x.test')")
                .params(OWNER).update();
        jdbc.sql("INSERT INTO store_membership (organization_id, store_id, user_id, role) VALUES (?, ?, ?, 'OWNER')")
                .params(ORG, STORE, OWNER).update();
        jdbc.sql("""
                INSERT INTO integration_connection (id, organization_id, store_id, provider, store_url, status,
                                                    webhook_endpoint_key, created_at, updated_at)
                VALUES (?, ?, ?, 'WOOCOMMERCE', 'https://shop.example.com', 'CONNECTED', ?, now(), now())
                """).params(CONNECTION, ORG, STORE, KEY).update();
        webhooks.saveSecret(storeAccess.scopeFor(OWNER, STORE).orElseThrow(), CONNECTION, SECRET);

        scope = storeAccess.systemScope("normalization-test", ORG, STORE).orElseThrow();
        Instant t0 = Instant.parse(T0 + "Z");
        catalog.upsertProduct(scope, CommerceSource.WOOCOMMERCE, new ProductData("14", "Kubek", "MUG", "simple",
                "publish", t0));
        mug = catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, new VariantData("14", "14", "Kubek", "MUG",
                "publish", StockTracking.VARIANT, Backorders.NO, t0));
        catalog.upsertProduct(scope, CommerceSource.WOOCOMMERCE, new ProductData("10", "Koszulka", null, "variable",
                "publish", t0));
        shirtS = variation("11", StockTracking.VARIANT, t0);
        shirtM = variation("12", StockTracking.PARENT, t0);
        shirtL = variation("13", StockTracking.PARENT, t0);
        for (UUID variant : List.of(mug, shirtS, shirtM, shirtL)) {
            inventory.record(scope, variant, new InventoryReading(49, StockStatus.IN_STOCK,
                    ReservationSemantics.ALREADY_ACCOUNTED_FOR, null, ObservationSource.IMPORT, t0, t0));
        }
    }

    @Test
    void oldEventAfterNewOneDoesNotRegressStock() {
        deliver("product.updated", simple(47, T2));
        deliver("product.updated", simple(48, T1));
        webhooks.processPending(10);

        assertThat(stock(mug)).isEqualTo(47);
        assertThat(entries()).extracting(InboxEntry::status, InboxEntry::outcome).containsExactly(
                org.assertj.core.groups.Tuple.tuple(InboxStatus.PROCESSED, "RECORDED"),
                org.assertj.core.groups.Tuple.tuple(InboxStatus.IGNORED, "STALE"));
        assertThat(events(OutboxEvents.INVENTORY_CHANGED)).isEqualTo(1);
    }

    @Test
    void sameEventArrivingOutOfOrderAfterProcessingIsStillStale() {
        deliver("product.updated", simple(47, T2));
        webhooks.processPending(10);
        deliver("product.updated", simple(48, T1));
        webhooks.processPending(10);

        assertThat(stock(mug)).isEqualTo(47);
        assertThat(events(OutboxEvents.INVENTORY_CHANGED)).isEqualTo(1);
    }

    @Test
    void repeatedDeliveryIsOneObservationAndOneEvent() {
        assertThat(deliver("product.updated", simple(47, T2))).isEqualTo(WebhookReceipt.ACCEPTED);
        assertThat(deliver("product.updated", simple(47, T2))).isEqualTo(WebhookReceipt.DUPLICATE);
        // The same state with a different body (e.g. field order) is caught by the stock comparison.
        deliver("product.updated", simple(47, T2).replace("{\"id\":14,", "{\"sku\":\"MUG\",\"id\":14,"));
        webhooks.processPending(10);

        assertThat(entries()).extracting(InboxEntry::outcome).containsExactly("RECORDED", "DUPLICATE");
        assertThat(webhookObservations(mug)).isEqualTo(1);
        assertThat(events(OutboxEvents.INVENTORY_CHANGED)).isEqualTo(1);
    }

    @Test
    void sameSecondWithDifferentStockAsksForVerificationInsteadOfGuessing() {
        deliver("product.updated", simple(47, T2));
        deliver("product.updated", simple(46, T2));
        webhooks.processPending(10);

        assertThat(stock(mug)).isEqualTo(47);
        assertThat(entries()).extracting(InboxEntry::outcome).containsExactly("RECORDED", "AMBIGUOUS");
        assertThat(events(OutboxEvents.INVENTORY_VERIFICATION_REQUESTED)).isEqualTo(1);
    }

    @Test
    void parentStockUpdatesOnlyVariantsSharingIt() {
        deliver("product.updated", """
                {"id":10,"name":"Koszulka","type":"variable","status":"publish","manage_stock":true,
                 "stock_quantity":30,"stock_status":"instock","backorders":"no","date_modified_gmt":"%s"}
                """.formatted(T2));
        webhooks.processPending(10);

        assertThat(stock(shirtM)).isEqualTo(30);
        assertThat(stock(shirtL)).isEqualTo(30);
        assertThat(stock(shirtS)).isEqualTo(49);
        assertThat(events(OutboxEvents.INVENTORY_CHANGED)).isEqualTo(2);
    }

    @Test
    void variationWithOwnStockAndUnknownParent() {
        deliver("product.updated", """
                {"id":11,"parent_id":10,"name":"Koszulka - S","type":"variation","status":"publish",
                 "manage_stock":true,"stock_quantity":5,"stock_status":"instock","backorders":"no",
                 "date_modified_gmt":"%s"}
                """.formatted(T2));
        deliver("product.updated", """
                {"id":99,"parent_id":98,"type":"variation","status":"publish","manage_stock":true,
                 "stock_quantity":5,"stock_status":"instock","date_modified_gmt":"%s"}
                """.formatted(T2));
        webhooks.processPending(10);

        assertThat(stock(shirtS)).isEqualTo(5);
        assertThat(catalog.variant(scope, shirtS).orElseThrow().name()).isEqualTo("Koszulka – 11");
        assertThat(entries()).extracting(InboxEntry::outcome).containsExactly("RECORDED", "VERIFY_UNKNOWN_PRODUCT");
        assertThat(events(OutboxEvents.INVENTORY_VERIFICATION_REQUESTED)).isEqualTo(1);
    }

    @Test
    void legacyPayloadWithoutStockStatusDoesNotChangeStock() {
        deliver("product.updated", """
                {"id":14,"name":"Kubek","type":"simple","status":"publish","manage_stock":true,
                 "stock_quantity":1,"in_stock":true,"date_modified_gmt":"%s"}
                """.formatted(T2));
        webhooks.processPending(10);

        assertThat(stock(mug)).isEqualTo(49);
        assertThat(entries()).extracting(InboxEntry::outcome).containsExactly("VERIFY_UNSUPPORTED_PAYLOAD");
        assertThat(events(OutboxEvents.INVENTORY_VERIFICATION_REQUESTED)).isEqualTo(1);
    }

    @Test
    void orderCreatedAndUpdatedWithTheSameStateIsOneEventAndOlderOrdersAreIgnored() {
        deliver("order.created", order("processing", T2));
        deliver("order.updated", order("processing", T2));
        deliver("order.updated", order("pending", T1));
        webhooks.processPending(10);

        assertThat(jdbc.sql("SELECT status FROM customer_order WHERE store_id = ?").params(STORE)
                .query(String.class).single()).isEqualTo("processing");
        assertThat(entries()).extracting(InboxEntry::outcome)
                .containsExactly("ORDER_UPSERTED", "ORDER_UPSERTED", "ORDER_STALE");
        assertThat(events(OutboxEvents.ORDER_CHANGED)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT payload ->> 'variantIds' FROM outbox_event WHERE event_type = ?")
                .params(OutboxEvents.ORDER_CHANGED).query(String.class).single()).contains(mug.toString());
        // Webhooks never claim completeness of a period (ADR-0008).
        assertThat(jdbc.sql("SELECT count(*) FROM order_sync_coverage").query(Long.class).single()).isZero();
    }

    @Test
    void reprocessingTheSameInboxItemWritesNoSecondEvent() {
        UUID inboxId = UUID.randomUUID();
        String legacy = "{\"id\":14,\"type\":\"simple\",\"status\":\"publish\",\"date_modified_gmt\":\"" + T2 + "\"}";
        normalizer.apply(scope, inboxId, "product.updated", legacy);
        normalizer.apply(scope, inboxId, "product.updated", legacy);

        assertThat(events(OutboxEvents.INVENTORY_VERIFICATION_REQUESTED)).isEqualTo(1);
    }

    @Test
    void unreadablePayloadFailsAtOnceAndLeavesNoPartialState() {
        deliver("order.created", order("processing", T2).replace("\"date_created_gmt\":\"" + T1 + "\",", ""));
        webhooks.processPending(10);

        assertThat(entries()).extracting(InboxEntry::status, InboxEntry::outcome).containsExactly(
                org.assertj.core.groups.Tuple.tuple(InboxStatus.FAILED, "INVALID_PAYLOAD"));
        assertThat(jdbc.sql("SELECT count(*) FROM customer_order").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event").query(Long.class).single()).isZero();
        assertThat(webhooks.processPending(10)).isZero();
    }

    private UUID variation(String id, StockTracking tracking, Instant at) {
        return catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, new VariantData(id, "10", "Koszulka – " + id,
                null, "publish", tracking, Backorders.NO, at));
    }

    private static String simple(int quantity, String modified) {
        return """
                {"id":14,"name":"Kubek","sku":"MUG","type":"simple","status":"publish","manage_stock":true,\
                "stock_quantity":%d,"stock_status":"instock","backorders":"no","date_modified_gmt":"%s"}"""
                .formatted(quantity, modified);
    }

    private static String order(String status, String modified) {
        return """
                {"id":501,"status":"%s","currency":"PLN","total":"79.00","total_tax":"14.77","discount_total":"0.00",\
                "shipping_total":"0.00","date_created_gmt":"%s","date_modified_gmt":"%s",\
                "line_items":[{"id":9,"product_id":14,"variation_id":0,"quantity":1,"total":"64.23","total_tax":"14.77"}]}"""
                .formatted(status, T1, modified);
    }

    private WebhookReceipt deliver(String topic, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String signature = Base64.getEncoder().encodeToString(WooWebhooks.hmacSha256(SECRET, bytes));
        return webhooks.receive(KEY, new WebhookHeaders("application/json", topic, signature, "1", "d"), bytes);
    }

    private Integer stock(UUID variant) {
        return inventory.latest(scope, variant).orElseThrow().reading().stockQuantity();
    }

    private long webhookObservations(UUID variant) {
        return jdbc.sql("SELECT count(*) FROM inventory_observation WHERE variant_id = ? AND observation_source = 'WEBHOOK'")
                .params(variant).query(Long.class).single();
    }

    private List<InboxEntry> entries() {
        return inbox.list(scope, CONNECTION);
    }

    private long events(String type) {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE event_type = ? AND store_id = ?")
                .params(type, STORE).query(Long.class).single();
    }
}
