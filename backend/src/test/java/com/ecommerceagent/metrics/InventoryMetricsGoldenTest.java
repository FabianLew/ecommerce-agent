package com.ecommerceagent.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Golden scenarios IG-01, IG-02, IG-03 and IG-25 from document 03 §15 for Metrics Engine v1 (B-17).
 * Deterministic: exact values are required here; LLM quality is evaluated separately (CLAUDE.md).
 *
 * <p>Scenario from 03 §2: store in Europe/Warsaw, calculation at 14.11.2026 09:00:30 local (08:00:30 UTC),
 * 18 sellable units, 49 units sold in the 7 full local days 07.11–13.11.
 */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class InventoryMetricsGoldenTest {

    static final UUID ORG = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    static final UUID STORE = UUID.fromString("b1000000-0000-4000-8000-00000000000a");
    static final UUID OTHER_STORE = UUID.fromString("b1000000-0000-4000-8000-00000000000b");
    static final Instant AS_OF = Instant.parse("2026-11-14T08:00:30Z");
    /** 07.11.2026 00:00 and 14.11.2026 00:00 in Europe/Warsaw (CET, UTC+1). */
    static final Instant WINDOW_FROM = Instant.parse("2026-11-06T23:00:00Z");
    static final Instant WINDOW_TO = Instant.parse("2026-11-13T23:00:00Z");

    @Autowired
    InventoryMetrics metrics;
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

    TrustedStoreScope scope;
    TrustedStoreScope otherScope;
    int orderSeq;

    @BeforeEach
    void store() {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Metrics org')").param(ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone)
                VALUES (?, ?, 'store-01', 'PLN', 'Europe/Warsaw'), (?, ?, 'other', 'PLN', 'Europe/Warsaw')
                """).params(STORE, ORG, OTHER_STORE, ORG).update();
        scope = storeAccess.systemScope("metrics-test", ORG, STORE).orElseThrow();
        otherScope = storeAccess.systemScope("metrics-test", ORG, OTHER_STORE).orElseThrow();
        orderSeq = 0;
    }

    UUID variant(TrustedStoreScope in, String externalId, StockTracking tracking, Backorders backorders) {
        catalog.upsertProduct(in, CommerceSource.WOOCOMMERCE,
                new ProductData("812", "Serum", "SERUM", "variable", "publish", null));
        return catalog.upsertVariant(in, CommerceSource.WOOCOMMERCE, new VariantData(externalId, "812",
                "Serum 30 ml", "SERUM-30", "publish", tracking, backorders, null));
    }

    UUID serum() {
        return variant(scope, "826", StockTracking.VARIANT, Backorders.NO);
    }

    void sold(TrustedStoreScope in, String variantExternalId, String status, Instant placedAt, int quantity) {
        orderSeq++;
        orders.upsert(in, CommerceSource.WOOCOMMERCE, new OrderData("o-" + orderSeq, status, "PLN",
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, placedAt, placedAt, null,
                placedAt, List.of(new OrderLineData("l-" + orderSeq, variantExternalId, "812", "SERUM-30", quantity,
                        new BigDecimal("100.00"), BigDecimal.ZERO))));
    }

    void stock(UUID variantId, Integer quantity, StockStatus status, ReservationSemantics semantics, Integer reserved,
            Instant observedAt) {
        inventory.record(scope, variantId, new InventoryReading(quantity, status, semantics, reserved,
                ObservationSource.POLL, observedAt, observedAt));
    }

    /** 49 qualifying units, one order per local day, plus everything that must not count. */
    void igSales() {
        String[] statuses = {"processing", "completed", "on-hold"};
        for (int day = 0; day < 7; day++) {
            sold(scope, "826", statuses[day % 3], WINDOW_FROM.plusSeconds(day * 86_400L + 36_000), 7);
        }
        sold(scope, "826", "processing", WINDOW_FROM.minusSeconds(1), 5);   // 06.11 23:59:59 local
        sold(scope, "826", "processing", WINDOW_TO, 5);                     // 14.11 00:00:00 local
        sold(scope, "826", "cancelled", WINDOW_FROM.plusSeconds(50_000), 3);
        sold(scope, "826", "pending", WINDOW_FROM.plusSeconds(60_000), 2);
        sold(scope, "826", "refunded", WINDOW_FROM.plusSeconds(70_000), 1);
        variant(otherScope, "826", StockTracking.VARIANT, Backorders.NO);
        sold(otherScope, "826", "processing", WINDOW_FROM.plusSeconds(80_000), 100);
        orders.recordCoverage(scope, CommerceSource.WOOCOMMERCE, Instant.parse("2026-10-31T00:00:00Z"), AS_OF);
    }

    @Test
    void ig01_daysOfCoverFor18UnitsAnd49SoldIn7Days() {
        UUID serum = serum();
        igSales();
        stock(serum, 40, StockStatus.IN_STOCK, ReservationSemantics.ALREADY_ACCOUNTED_FOR, null,
                Instant.parse("2026-11-10T10:00:00Z"));
        stock(serum, 18, StockStatus.IN_STOCK, ReservationSemantics.ALREADY_ACCOUNTED_FOR, null,
                Instant.parse("2026-11-14T08:00:15Z"));
        stock(serum, 5, StockStatus.IN_STOCK, ReservationSemantics.ALREADY_ACCOUNTED_FOR, null,
                Instant.parse("2026-11-14T09:00:00Z")); // after asOf: must not be used

        InventoryCoverage coverage = metrics.coverage(scope, serum, AS_OF);

        assertThat(coverage.unitsSold().value()).isEqualByComparingTo("49");
        assertThat(coverage.unitsSold().quality()).isEqualTo(MetricQuality.CONFIRMED);
        assertThat(coverage.unitsSold().period().from()).isEqualTo(WINDOW_FROM);
        assertThat(coverage.unitsSold().period().toExclusive()).isEqualTo(WINDOW_TO);
        assertThat(coverage.velocityPerDay().value()).isEqualByComparingTo("7.0000");
        assertThat(coverage.velocityPerDay().quality()).isEqualTo(MetricQuality.ESTIMATED);
        assertThat(coverage.velocityPerDay().limitations()).isEmpty();
        assertThat(coverage.daysOfCover().value()).isEqualTo(new BigDecimal("2.5714"));
        assertThat(coverage.daysOfCover().quality()).isEqualTo(MetricQuality.ESTIMATED);
        assertThat(coverage.daysOfCover().limitations()).isEmpty();
        assertThat(coverage.daysOfCover().definitionVersion()).isEqualTo("days_of_cover@v1");
        assertThat(coverage.stock().reading().stockQuantity()).isEqualTo(18);
    }

    @Test
    void ig02_reservationsAreNeverSubtractedTwice() {
        UUID serum = serum();
        igSales();
        // Adapter where stock already includes reservations (WooCommerce, ADR-0008).
        stock(serum, 18, StockStatus.IN_STOCK, ReservationSemantics.ALREADY_ACCOUNTED_FOR, null,
                Instant.parse("2026-11-12T08:00:00Z"));
        BigDecimal accounted = metrics.coverage(scope, serum, AS_OF).daysOfCover().value();
        // Adapter reporting physical stock and reservations separately: 20 - 2 = 18.
        stock(serum, 20, StockStatus.IN_STOCK, ReservationSemantics.SEPARATE_RESERVATIONS, 2,
                Instant.parse("2026-11-14T08:00:00Z"));
        BigDecimal separate = metrics.coverage(scope, serum, AS_OF).daysOfCover().value();

        assertThat(accounted).isEqualTo(separate).isEqualTo(new BigDecimal("2.5714"));

        // Separate reservations that were not reported: no guess.
        stock(serum, 20, StockStatus.IN_STOCK, ReservationSemantics.SEPARATE_RESERVATIONS, null,
                Instant.parse("2026-11-14T08:00:10Z"));
        MetricValue unknown = metrics.coverage(scope, serum, AS_OF).daysOfCover();
        assertThat(unknown.value()).isNull();
        assertThat(unknown.unavailableReason()).isEqualTo(MetricReason.AVAILABLE_STOCK_UNKNOWN);
    }

    @Test
    void ig03_zeroSalesGiveNullCoverNotInfinity() {
        UUID serum = serum();
        orders.recordCoverage(scope, CommerceSource.WOOCOMMERCE, Instant.parse("2026-10-31T00:00:00Z"), AS_OF);
        stock(serum, 18, StockStatus.IN_STOCK, ReservationSemantics.ALREADY_ACCOUNTED_FOR, null,
                Instant.parse("2026-11-14T08:00:15Z"));

        InventoryCoverage coverage = metrics.coverage(scope, serum, AS_OF);

        assertThat(coverage.unitsSold().value()).isEqualByComparingTo("0");
        assertThat(coverage.velocityPerDay().value()).isEqualByComparingTo("0");
        assertThat(coverage.daysOfCover().value()).isNull();
        assertThat(coverage.daysOfCover().quality()).isEqualTo(MetricQuality.UNAVAILABLE);
        assertThat(coverage.daysOfCover().unavailableReason()).isEqualTo(MetricReason.NO_SALES_IN_WINDOW);
    }

    @Test
    void ig03_incompleteSevenDaysGiveNullInsteadOfLowSales() {
        UUID serum = serum();
        igSales();
        jdbc.sql("DELETE FROM order_sync_coverage WHERE store_id = ?").param(STORE).update();
        stock(serum, 18, StockStatus.IN_STOCK, ReservationSemantics.ALREADY_ACCOUNTED_FOR, null, AS_OF);

        InventoryCoverage none = metrics.coverage(scope, serum, AS_OF);
        // Orders imported only from 10.11: the first days of the window are unknown.
        orders.recordCoverage(scope, CommerceSource.WOOCOMMERCE, Instant.parse("2026-11-10T00:00:00Z"), AS_OF);
        InventoryCoverage partial = metrics.coverage(scope, serum, AS_OF);

        for (InventoryCoverage coverage : List.of(none, partial)) {
            for (MetricValue value : List.of(coverage.unitsSold(), coverage.velocityPerDay(), coverage.daysOfCover())) {
                assertThat(value.value()).isNull();
                assertThat(value.quality()).isEqualTo(MetricQuality.UNAVAILABLE);
                assertThat(value.unavailableReason()).isEqualTo(MetricReason.SALES_HISTORY_INCOMPLETE);
            }
        }
    }

    @Test
    void ig25_stockoutInTheWindowMarksTheForecastUncertain() {
        UUID serum = serum();
        igSales();
        stock(serum, 0, StockStatus.OUT_OF_STOCK, ReservationSemantics.ALREADY_ACCOUNTED_FOR, null,
                Instant.parse("2026-11-09T12:00:00Z"));
        stock(serum, 18, StockStatus.IN_STOCK, ReservationSemantics.ALREADY_ACCOUNTED_FOR, null,
                Instant.parse("2026-11-14T08:00:15Z"));

        InventoryCoverage coverage = metrics.coverage(scope, serum, AS_OF);

        assertThat(coverage.daysOfCover().value()).isEqualTo(new BigDecimal("2.5714"));
        assertThat(coverage.velocityPerDay().limitations()).containsExactly(MetricReason.STOCKOUT_IN_WINDOW);
        assertThat(coverage.daysOfCover().limitations()).containsExactly(MetricReason.STOCKOUT_IN_WINDOW);
    }

    @Test
    void stockWithoutHistoryInTheWindowIsFlagged() {
        UUID serum = serum();
        igSales();
        stock(serum, 18, StockStatus.IN_STOCK, ReservationSemantics.ALREADY_ACCOUNTED_FOR, null,
                Instant.parse("2026-11-14T08:00:15Z"));

        assertThat(metrics.coverage(scope, serum, AS_OF).daysOfCover().limitations())
                .containsExactly(MetricReason.STOCK_HISTORY_UNVERIFIED);
    }

    @Test
    void coverIsUnavailableWhenStockIsNotTrackedSharedOrUnobserved() {
        igSales();
        UUID untracked = variant(scope, "827", StockTracking.NONE, Backorders.NO);
        UUID shared = variant(scope, "828", StockTracking.PARENT, Backorders.NO);
        UUID unobserved = variant(scope, "826", StockTracking.VARIANT, Backorders.NO);
        UUID backorders = variant(scope, "829", StockTracking.VARIANT, Backorders.NOTIFY);
        sold(scope, "829", "processing", WINDOW_FROM.plusSeconds(3_600), 7);
        stock(backorders, -2, StockStatus.ON_BACKORDER, ReservationSemantics.ALREADY_ACCOUNTED_FOR, null,
                Instant.parse("2026-11-14T08:00:00Z"));

        assertThat(metrics.coverage(scope, untracked, AS_OF).daysOfCover().unavailableReason())
                .isEqualTo(MetricReason.STOCK_NOT_TRACKED);
        assertThat(metrics.coverage(scope, shared, AS_OF).daysOfCover().unavailableReason())
                .isEqualTo(MetricReason.SHARED_STOCK_NOT_SUPPORTED);
        assertThat(metrics.coverage(scope, unobserved, AS_OF).daysOfCover().unavailableReason())
                .isEqualTo(MetricReason.NO_STOCK_OBSERVATION);
        MetricValue oversold = metrics.coverage(scope, backorders, AS_OF).daysOfCover();
        assertThat(oversold.value()).isEqualByComparingTo("0");
        assertThat(oversold.limitations()).contains(MetricReason.BACKORDERS_ALLOWED);
    }
}
