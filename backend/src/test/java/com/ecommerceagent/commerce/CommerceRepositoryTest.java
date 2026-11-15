package com.ecommerceagent.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

/**
 * B-11: commerce repositories. Includes the runtime safety part of ADR-0005: data of store A is neither
 * visible nor writable with the scope of store B, also when B guesses A's IDs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class CommerceRepositoryTest {

    static final UUID ORG = UUID.fromString("80000000-0000-4000-8000-000000000001");
    static final UUID STORE_A = UUID.fromString("80000000-0000-4000-8000-00000000000a");
    static final UUID STORE_B = UUID.fromString("80000000-0000-4000-8000-00000000000b");
    static final Instant T0 = Instant.parse("2026-11-10T10:00:00Z");
    static final CommerceSource WOO = CommerceSource.WOOCOMMERCE;

    @Autowired
    CatalogRepository catalog;

    @Autowired
    InventoryRepository inventory;

    @Autowired
    OrderRepository orders;

    @Autowired
    StoreAccess storeAccess;

    @Autowired
    FixtureDb jdbc;

    TrustedStoreScope scopeA;
    TrustedStoreScope scopeB;

    @BeforeEach
    void stores() {
        jdbc.sql("DELETE FROM order_sync_coverage WHERE organization_id = ?").param(ORG).update();
        jdbc.sql("DELETE FROM order_line WHERE organization_id = ?").param(ORG).update();
        jdbc.sql("DELETE FROM customer_order WHERE organization_id = ?").param(ORG).update();
        jdbc.sql("DELETE FROM inventory_observation WHERE organization_id = ?").param(ORG).update();
        jdbc.sql("DELETE FROM product_variant WHERE organization_id = ?").param(ORG).update();
        jdbc.sql("DELETE FROM product WHERE organization_id = ?").param(ORG).update();
        jdbc.sql("DELETE FROM store_membership WHERE organization_id = ?").param(ORG).update();
        jdbc.sql("DELETE FROM store WHERE organization_id = ?").param(ORG).update();
        jdbc.sql("DELETE FROM organization WHERE id = ?").param(ORG).update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Commerce org')").param(ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone)
                VALUES (?, ?, 'A', 'PLN', 'Europe/Warsaw'), (?, ?, 'B', 'PLN', 'Europe/Warsaw')
                """).params(STORE_A, ORG, STORE_B, ORG).update();
        scopeA = storeAccess.systemScope("commerce-test", ORG, STORE_A).orElseThrow();
        scopeB = storeAccess.systemScope("commerce-test", ORG, STORE_B).orElseThrow();
    }

    UUID serumVariant(TrustedStoreScope scope) {
        catalog.upsertProduct(scope, WOO, new ProductData("812", "Serum", null, "variable", "publish", T0));
        return catalog.upsertVariant(scope, WOO, new VariantData("826", "812", "Serum 30 ml", "SERUM-30", "publish",
                StockTracking.VARIANT, Backorders.NO, T0));
    }

    static InventoryReading stock(int quantity, Instant observedAt) {
        return new InventoryReading(quantity, quantity > 0 ? StockStatus.IN_STOCK : StockStatus.OUT_OF_STOCK,
                ReservationSemantics.ALREADY_ACCOUNTED_FOR, null, ObservationSource.IMPORT, observedAt, observedAt);
    }

    static OrderData order(String id, String status, Instant placedAt, Instant updatedAt, int quantity) {
        return new OrderData(id, status, "PLN", new BigDecimal("123.0000"), new BigDecimal("23.0000"),
                BigDecimal.ZERO, new BigDecimal("10.00"), placedAt, placedAt, null, updatedAt,
                List.of(new OrderLineData(id + "-1", "826", "812", "SERUM-30", quantity, new BigDecimal("100.00"),
                        new BigDecimal("23.00"))));
    }

    @Test
    void upsertsAreIdempotentAndNeverMoveBackInSourceTime() {
        UUID first = serumVariant(scopeA);
        UUID again = serumVariant(scopeA);
        assertThat(again).isEqualTo(first);

        catalog.upsertVariant(scopeA, WOO, new VariantData("826", "812", "Serum 30 ml v2", "SERUM-30", "publish",
                StockTracking.VARIANT, Backorders.NO, T0.plusSeconds(60)));
        catalog.upsertVariant(scopeA, WOO, new VariantData("826", "812", "stale name", "SERUM-30", "publish",
                StockTracking.VARIANT, Backorders.NO, T0.plusSeconds(30)));

        assertThat(catalog.variant(scopeA, first).orElseThrow().name()).isEqualTo("Serum 30 ml v2");
        assertThat(catalog.productCount(scopeA)).isEqualTo(1);
    }

    @Test
    void catalogAndInventoryOfStoreAAreInvisibleToStoreB() {
        UUID variantA = serumVariant(scopeA);
        inventory.record(scopeA, variantA, stock(18, T0));

        assertThat(catalog.variant(scopeB, variantA)).isEmpty();
        assertThat(catalog.variants(scopeB)).isEmpty();
        assertThat(catalog.variantByExternalId(scopeB, WOO, "826")).isEmpty();
        assertThat(inventory.latest(scopeB, variantA)).isEmpty();
        assertThat(inventory.observed(scopeB, variantA, T0.minusSeconds(1), T0.plusSeconds(1))).isEmpty();
        assertThatThrownBy(() -> inventory.record(scopeB, variantA, stock(0, T0.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(inventory.latest(scopeA, variantA).orElseThrow().reading().stockQuantity()).isEqualTo(18);
    }

    @Test
    void sameExternalIdsInTwoStoresAreSeparateRecords() {
        UUID variantA = serumVariant(scopeA);
        UUID variantB = serumVariant(scopeB);
        orders.upsert(scopeA, WOO, order("1001", "processing", T0, T0, 3));
        orders.upsert(scopeB, WOO, order("1001", "processing", T0, T0, 5));

        assertThat(variantB).isNotEqualTo(variantA);
        Set<String> paid = Set.of("processing", "completed");
        assertThat(orders.unitsSold(scopeA, variantA, T0, T0.plusSeconds(1), paid)).isEqualTo(3);
        assertThat(orders.unitsSold(scopeB, variantB, T0, T0.plusSeconds(1), paid)).isEqualTo(5);
        // Asking with B's scope for A's variant returns nothing, not A's sales.
        assertThat(orders.unitsSold(scopeB, variantA, T0, T0.plusSeconds(1), paid)).isZero();
    }

    @Test
    void orderLinesAreLinkedOnlyToVariantsOfTheSameStore() {
        serumVariant(scopeA);
        orders.upsert(scopeB, WOO, order("2001", "processing", T0, T0, 1));

        assertThat(jdbc.sql("SELECT variant_id FROM order_line WHERE store_id = ?").param(STORE_B)
                .query(UUID.class).list()).containsOnlyNulls();
    }

    @Test
    void orderUpdatesReplaceLinesAndStaleVersionsAreIgnored() {
        UUID variant = serumVariant(scopeA);
        Set<String> paid = Set.of("processing", "completed");
        assertThat(orders.upsert(scopeA, WOO, order("3001", "processing", T0, T0, 2))).isTrue();
        assertThat(orders.upsert(scopeA, WOO, order("3001", "completed", T0, T0.plusSeconds(60), 4))).isTrue();
        assertThat(orders.upsert(scopeA, WOO, order("3001", "cancelled", T0, T0.plusSeconds(30), 9))).isFalse();

        assertThat(orders.unitsSold(scopeA, variant, T0, T0.plusSeconds(1), paid)).isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM order_line WHERE store_id = ?").param(STORE_A)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void unitsSoldUsesHalfOpenPeriodAndStatuses() {
        UUID variant = serumVariant(scopeA);
        Instant from = Instant.parse("2026-11-06T23:00:00Z");
        Instant to = Instant.parse("2026-11-13T23:00:00Z");
        orders.upsert(scopeA, WOO, order("4001", "processing", from, from, 1));
        orders.upsert(scopeA, WOO, order("4002", "completed", to.minusSeconds(1), to, 2));
        orders.upsert(scopeA, WOO, order("4003", "completed", to, to, 4));
        orders.upsert(scopeA, WOO, order("4004", "cancelled", from.plusSeconds(10), from, 8));
        orders.upsert(scopeA, WOO, order("4005", "pending", from.plusSeconds(10), from, 16));

        assertThat(orders.unitsSold(scopeA, variant, from, to, Set.of("processing", "completed"))).isEqualTo(3);
        assertThat(orders.unitsSold(scopeA, variant, from, to, Set.of())).isZero();
        assertThat(orders.orderCount(scopeA, WOO, from)).isEqualTo(5);
    }

    @Test
    void coverageExtendsWhenOverlappingAndIsReplacedWhenDisjoint() {
        Instant day = Instant.parse("2026-11-01T00:00:00Z");
        orders.recordCoverage(scopeA, WOO, day, day.plusSeconds(86_400 * 14));
        orders.recordCoverage(scopeA, WOO, day.plusSeconds(86_400 * 13), day.plusSeconds(86_400 * 15));
        assertThat(orders.coverage(scopeA, WOO).orElseThrow())
                .isEqualTo(new OrderCoverage(WOO, day, day.plusSeconds(86_400 * 15)));

        orders.recordCoverage(scopeA, WOO, day.plusSeconds(86_400 * 20), day.plusSeconds(86_400 * 21));
        assertThat(orders.coverage(scopeA, WOO).orElseThrow().completeFrom()).isEqualTo(day.plusSeconds(86_400 * 20));
        assertThat(orders.coverage(scopeB, WOO)).isEmpty();
    }

    @Test
    void databaseRejectsAVariantPointingAtAnotherStoresProduct() {
        UUID productA = catalog.upsertProduct(scopeA, WOO, new ProductData("900", "P", null, "simple", "publish", T0));

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO product_variant (id, organization_id, store_id, product_id, source, external_id,
                                                     name, status, stock_tracking, backorders, ingested_at)
                        VALUES (?, ?, ?, ?, 'WOOCOMMERCE', '900', 'P', 'publish', 'VARIANT', 'NO', now())
                        """).params(UUID.randomUUID(), ORG, STORE_B, productA).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void reservationsAreNeverSubtractedTwice() {
        // IG-02: two adapters with different semantics describe the same sellable quantity.
        InventoryReading accounted = new InventoryReading(18, StockStatus.IN_STOCK,
                ReservationSemantics.ALREADY_ACCOUNTED_FOR, null, ObservationSource.POLL, null, T0);
        InventoryReading separate = new InventoryReading(21, StockStatus.IN_STOCK,
                ReservationSemantics.SEPARATE_RESERVATIONS, 3, ObservationSource.POLL, null, T0);
        InventoryReading separateWithoutReservations = new InventoryReading(21, StockStatus.IN_STOCK,
                ReservationSemantics.SEPARATE_RESERVATIONS, null, ObservationSource.POLL, null, T0);

        assertThat(accounted.availableToSell()).isEqualTo(18);
        assertThat(separate.availableToSell()).isEqualTo(18);
        assertThat(separateWithoutReservations.availableToSell()).isNull();
        assertThatThrownBy(() -> new InventoryReading(18, StockStatus.IN_STOCK,
                ReservationSemantics.ALREADY_ACCOUNTED_FOR, 3, ObservationSource.POLL, null, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
