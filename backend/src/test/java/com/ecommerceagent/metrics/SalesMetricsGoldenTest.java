package com.ecommerceagent.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.TestcontainersConfiguration;
import com.ecommerceagent.commerce.Backorders;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.OrderData;
import com.ecommerceagent.commerce.OrderLineData;
import com.ecommerceagent.commerce.OrderRepository;
import com.ecommerceagent.commerce.ProductData;
import com.ecommerceagent.commerce.StockTracking;
import com.ecommerceagent.commerce.VariantData;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.SalesMetrics.LocalWindow;
import com.ecommerceagent.metrics.SalesMetrics.SalesSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Golden tests for net_sales, qualifying_orders, aov and variant_net_sales v1 (ADR-0019). Exact values. */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class SalesMetricsGoldenTest {

    static final UUID ORG = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    static final UUID STORE = UUID.fromString("c1000000-0000-4000-8000-00000000000a");
    static final UUID OTHER_STORE = UUID.fromString("c1000000-0000-4000-8000-00000000000b");
    static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    static final Instant AS_OF = Instant.parse("2026-11-14T08:00:00Z");

    @Autowired
    SalesMetrics metrics;
    @Autowired
    OrderRepository orders;
    @Autowired
    CatalogRepository catalog;
    @Autowired
    StoreAccess storeAccess;
    @Autowired
    FixtureDb jdbc;

    TrustedStoreScope scope;
    TrustedStoreScope other;
    UUID serum;
    int seq;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Org')").param(ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone)
                VALUES (?, ?, 'a', 'PLN', 'Europe/Warsaw'), (?, ?, 'b', 'PLN', 'Europe/Warsaw')
                """).params(STORE, ORG, OTHER_STORE, ORG).update();
        scope = storeAccess.systemScope("test", ORG, STORE).orElseThrow();
        other = storeAccess.systemScope("test", ORG, OTHER_STORE).orElseThrow();
        catalog.upsertProduct(scope, CommerceSource.WOOCOMMERCE, new ProductData("812", "Serum", null, "simple",
                "publish", AS_OF));
        serum = catalog.upsertVariant(scope, CommerceSource.WOOCOMMERCE, new VariantData("812", "812", "Serum",
                "SERUM-30", "publish", StockTracking.VARIANT, Backorders.NO, AS_OF));
        // Coverage 01.11 00:00 – 14.11 00:00 local.
        orders.recordCoverage(scope, CommerceSource.WOOCOMMERCE, local(2026, 11, 1), local(2026, 11, 14));
        orders.recordCoverage(other, CommerceSource.WOOCOMMERCE, local(2026, 11, 1), local(2026, 11, 14));
    }

    static Instant local(int y, int m, int d) {
        return LocalDate.of(y, m, d).atStartOfDay(WARSAW).toInstant();
    }

    private void order(TrustedStoreScope in, String status, String currency, String total, String tax, String shipping,
            Instant placedAt, int units, String lineTotal) {
        seq++;
        orders.upsert(in, CommerceSource.WOOCOMMERCE, new OrderData("o" + seq, status, currency, new BigDecimal(total),
                new BigDecimal(tax), BigDecimal.ZERO, new BigDecimal(shipping), placedAt, null, null, placedAt,
                List.of(new OrderLineData("l" + seq, "812", "812", "SERUM-30", units, new BigDecimal(lineTotal),
                        BigDecimal.ZERO))));
    }

    @Test
    void netSalesExcludeTaxShippingOtherCurrenciesAndNonQualifyingOrders() {
        // 10.11 local: 123.00 total, 23.00 tax, 10.00 shipping -> 90.00 net.
        order(scope, "processing", "PLN", "123.00", "23.00", "10.00", Instant.parse("2026-11-10T09:00:00Z"), 2, "81.30");
        // 10.11 local, 23:30 local = 22:30 UTC: still the 10th in Warsaw.
        order(scope, "completed", "PLN", "61.50", "11.50", "0.00", Instant.parse("2026-11-10T22:30:00Z"), 1, "50.00");
        order(scope, "cancelled", "PLN", "999.00", "0", "0", Instant.parse("2026-11-11T09:00:00Z"), 5, "999.00");
        order(scope, "processing", "EUR", "50.00", "0", "0", Instant.parse("2026-11-11T09:00:00Z"), 1, "50.00");
        order(other, "processing", "PLN", "500.00", "0", "0", Instant.parse("2026-11-11T09:00:00Z"), 9, "500.00");

        SalesSummary summary = metrics.sales(scope, new LocalWindow(LocalDate.of(2026, 11, 8),
                LocalDate.of(2026, 11, 13), WARSAW), AS_OF);

        assertThat(summary.netSales().value()).isEqualByComparingTo("140.00");
        assertThat(summary.netSales().quality()).isEqualTo(MetricQuality.ESTIMATED);
        assertThat(summary.netSales().limitations()).containsExactly(MetricReason.OTHER_CURRENCY_EXCLUDED,
                MetricReason.REFUNDS_NOT_INCLUDED);
        assertThat(summary.orders().value()).isEqualByComparingTo("2");
        assertThat(summary.aov().value()).isEqualByComparingTo("70.00");
        assertThat(summary.otherCurrencyOrders()).isEqualTo(1);
        assertThat(summary.series()).hasSize(6);
        assertThat(summary.series().get(2).day()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(summary.series().get(2).orders()).isEqualTo(2);
        assertThat(summary.series().get(0).netSales()).isEqualByComparingTo("0.00");
        assertThat(metrics.variantSales(scope, summary.window()).get(serum).netSales()).isEqualByComparingTo("131.30");
        assertThat(metrics.variantSales(scope, summary.window()).get(serum).units()).isEqualTo(3);
    }

    @Test
    void aPartlyCoveredWindowIsEstimatedAndUncoveredDaysAreNull() {
        order(scope, "processing", "PLN", "10.00", "0", "0", Instant.parse("2026-11-12T09:00:00Z"), 1, "10.00");
        SalesSummary summary = metrics.sales(scope, new LocalWindow(LocalDate.of(2026, 11, 12),
                LocalDate.of(2026, 11, 15), WARSAW), AS_OF);
        assertThat(summary.covered().whole()).isFalse();
        assertThat(summary.orders().quality()).isEqualTo(MetricQuality.ESTIMATED);
        assertThat(summary.orders().limitations()).containsExactly(MetricReason.SALES_HISTORY_PARTIAL);
        assertThat(summary.series().get(1).orders()).isZero();
        assertThat(summary.series().get(2).orders()).isNull();
        assertThat(summary.series().get(3).netSales()).isNull();
    }

    @Test
    void anUncoveredWindowIsUnavailableAndNoOrdersMeanNoAov() {
        SalesSummary outside = metrics.sales(scope, new LocalWindow(LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 7), WARSAW), AS_OF);
        assertThat(outside.netSales().value()).isNull();
        assertThat(outside.netSales().unavailableReason()).isEqualTo(MetricReason.SALES_HISTORY_INCOMPLETE);
        assertThat(outside.series()).allSatisfy(point -> assertThat(point.orders()).isNull());

        SalesSummary empty = metrics.sales(scope, new LocalWindow(LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 3), WARSAW), AS_OF);
        assertThat(empty.netSales().value()).isEqualByComparingTo("0.00");
        assertThat(empty.orders().quality()).isEqualTo(MetricQuality.CONFIRMED);
        assertThat(empty.aov().value()).isNull();
        assertThat(empty.aov().unavailableReason()).isEqualTo(MetricReason.NO_ORDERS_IN_WINDOW);
    }

    @Test
    void thePreviousWindowHasTheSameLength() {
        LocalWindow window = new LocalWindow(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 13), WARSAW);
        assertThat(window.previous()).isEqualTo(new LocalWindow(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 7),
                WARSAW));
    }
}
