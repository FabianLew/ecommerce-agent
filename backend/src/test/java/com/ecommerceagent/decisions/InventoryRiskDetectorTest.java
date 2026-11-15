package com.ecommerceagent.decisions;

import com.ecommerceagent.FixtureDb;
import com.ecommerceagent.identity.StoreScopeBinding;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import com.ecommerceagent.metrics.InventoryMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B-19 (ADR-0011): detector {@code inventory-risk/1} opens an episode only after a series of current risk
 * observations, and a subject has at most one active episode even under concurrent opening (IG-05).
 *
 * <p>Scenario from 03 §2: 18 sellable units, 49 sold in the 7 full local days before 14.11.2026, so 2.5714
 * days of cover against the 3-day threshold.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class InventoryRiskDetectorTest {

    static final UUID ORG = UUID.fromString("f0000000-0000-4000-8000-000000000001");
    static final UUID STORE_A = UUID.fromString("f1000000-0000-4000-8000-00000000000a");
    static final UUID STORE_B = UUID.fromString("f1000000-0000-4000-8000-00000000000b");
    static final Instant AS_OF = Instant.parse("2026-11-14T08:00:30Z");
    /** 07.11.2026 00:00 in Europe/Warsaw. */
    static final Instant WINDOW_FROM = Instant.parse("2026-11-06T23:00:00Z");
    static final RiskDetectorSettings SETTINGS = new RiskDetectorSettings(new BigDecimal("3"), 2,
            Duration.ofMinutes(5), Duration.ofSeconds(120));

    @Autowired
    InventoryMetrics metrics;
    @Autowired
    Opportunities opportunities;
    @Autowired
    CatalogRepository catalog;
    @Autowired
    InventoryRepository inventory;
    @Autowired
    OrderRepository orders;
    @Autowired
    StoreAccess storeAccess;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    FixtureDb jdbc;

    @Autowired
    JdbcClient appJdbc;

    @Autowired
    StoreScopeBinding binding;

    final TestClock clock = new TestClock(AS_OF);
    InventoryRiskDetector detector;
    TrustedStoreScope scopeA;
    TrustedStoreScope scopeB;
    UUID serum;

    @BeforeEach
    void store() {
        jdbc.sql("TRUNCATE store_membership, app_user, store, organization CASCADE").update();
        jdbc.sql("INSERT INTO organization (id, name) VALUES (?, 'Risk org')").param(ORG).update();
        jdbc.sql("""
                INSERT INTO store (id, organization_id, name, currency, timezone)
                VALUES (?, ?, 'A', 'PLN', 'Europe/Warsaw'), (?, ?, 'B', 'PLN', 'Europe/Warsaw')
                """).params(STORE_A, ORG, STORE_B, ORG).update();
        scopeA = storeAccess.systemScope("risk-test", ORG, STORE_A).orElseThrow();
        scopeB = storeAccess.systemScope("risk-test", ORG, STORE_B).orElseThrow();
        clock.set(AS_OF);
        // Built by hand for its clock; the binding advisor gives it the store binding a bean has (ADR-0022).
        detector = (InventoryRiskDetector) binding.postProcessAfterInitialization(
                new InventoryRiskDetector(metrics, opportunities, SETTINGS, appJdbc, clock), "detector");

        catalog.upsertProduct(scopeA, CommerceSource.WOOCOMMERCE,
                new ProductData("812", "Serum", "SERUM", "variable", "publish", null));
        serum = catalog.upsertVariant(scopeA, CommerceSource.WOOCOMMERCE, new VariantData("826", "812",
                "Serum 30 ml", "SERUM-30", "publish", StockTracking.VARIANT, Backorders.NO, null));
        for (int day = 0; day < 7; day++) {
            Instant placedAt = WINDOW_FROM.plusSeconds(day * 86_400L + 36_000);
            orders.upsert(scopeA, CommerceSource.WOOCOMMERCE, new OrderData("o-" + day, "processing", "PLN",
                    new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, placedAt, placedAt,
                    null, placedAt, List.of(new OrderLineData("l-" + day, "826", "812", "SERUM-30", 7,
                            new BigDecimal("100.00"), BigDecimal.ZERO))));
        }
        orders.recordCoverage(scopeA, CommerceSource.WOOCOMMERCE, Instant.parse("2026-10-31T00:00:00Z"), AS_OF);
    }

    @Test
    void secondCurrentRiskObservationAfterTheGapOpensOneEpisode() {
        stock(18, AS_OF.minusSeconds(15));
        RiskAssessment first = detector.assess(scopeA, serum, null);
        assertThat(first.observation().outcome()).isEqualTo(RiskOutcome.AT_RISK);
        assertThat(first.observation().daysOfCover()).isEqualByComparingTo("2.5714");
        assertThat(first.observation().detectorVersion()).isEqualTo("inventory-risk/1");
        assertThat(first.opportunity()).isNull();
        assertThat(first.nextCheckAt()).isEqualTo(AS_OF.plus(Duration.ofMinutes(5)));

        // A second observation within the gap is recorded but does not open an episode yet.
        clock.set(AS_OF.plusSeconds(60));
        RiskAssessment early = detector.assess(scopeA, serum, AS_OF.plusSeconds(60));
        assertThat(early.opportunity()).isNull();
        assertThat(early.nextCheckAt()).isEqualTo(AS_OF.plus(Duration.ofMinutes(5)));

        // The confirming fresh read after the gap: the stored reading is older, the read confirms it.
        clock.set(AS_OF.plus(Duration.ofMinutes(5)));
        RiskAssessment confirmed = detector.assess(scopeA, serum, clock.instant());
        assertThat(confirmed.opened()).isTrue();
        assertThat(confirmed.opportunity().status()).isEqualTo(OpportunityStatus.OPEN);
        assertThat(confirmed.opportunity().subjectId()).isEqualTo(serum);
        assertThat(confirmed.observation().opportunityId()).isEqualTo(confirmed.opportunity().id());
        assertThat(confirmed.observation().stockConfirmedAt()).isEqualTo(clock.instant());
        assertThat(confirmed.nextCheckAt()).isNull();

        // Later observations attach to the active episode.
        clock.set(AS_OF.plus(Duration.ofMinutes(6)));
        RiskAssessment later = detector.assess(scopeA, serum, clock.instant());
        assertThat(later.opened()).isFalse();
        assertThat(later.opportunity().id()).isEqualTo(confirmed.opportunity().id());
        assertThat(detector.observations(scopeA, serum, 10)).hasSize(4);
        assertThat(count("opportunity")).isEqualTo(1);
    }

    @Test
    void observationThatIsNotRiskEndsTheSeries() {
        stock(18, AS_OF.minusSeconds(15));
        detector.assess(scopeA, serum, null);

        clock.set(AS_OF.plusSeconds(60));
        stock(40, clock.instant());
        assertThat(detector.assess(scopeA, serum, null).observation().outcome()).isEqualTo(RiskOutcome.NOT_AT_RISK);

        clock.set(AS_OF.plus(Duration.ofMinutes(6)));
        stock(18, clock.instant());
        RiskAssessment restarted = detector.assess(scopeA, serum, null);
        assertThat(restarted.opportunity()).isNull();
        assertThat(restarted.nextCheckAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
    }

    @Test
    void staleStockOrMissingSalesHistoryIsUnknownAndBreaksTheSeries() {
        stock(18, AS_OF.minusSeconds(15));
        detector.assess(scopeA, serum, null);

        // No fresh read for longer than the maximum stock age: the stock is not current.
        clock.set(AS_OF.plus(Duration.ofMinutes(5)));
        RiskAssessment stale = detector.assess(scopeA, serum, null);
        assertThat(stale.observation().outcome()).isEqualTo(RiskOutcome.UNKNOWN);
        assertThat(stale.observation().reason()).isEqualTo("STOCK_NOT_CURRENT");
        assertThat(stale.nextCheckAt()).isNull();

        // The unknown observation ended the series: a confirmed risk now starts a new one.
        RiskAssessment fresh = detector.assess(scopeA, serum, clock.instant());
        assertThat(fresh.observation().outcome()).isEqualTo(RiskOutcome.AT_RISK);
        assertThat(fresh.opportunity()).isNull();

        // Without order coverage the forecast is unavailable, never zero.
        jdbc.sql("DELETE FROM order_sync_coverage WHERE store_id = ?").param(STORE_A).update();
        RiskAssessment noHistory = detector.assess(scopeA, serum, clock.instant());
        assertThat(noHistory.observation().outcome()).isEqualTo(RiskOutcome.UNKNOWN);
        assertThat(noHistory.observation().reason()).isEqualTo("SALES_HISTORY_INCOMPLETE");
        assertThat(noHistory.observation().daysOfCover()).isNull();
        assertThat(count("opportunity")).isZero();
    }

    @Test
    void ig05_concurrentOpeningsCreateOneActiveEpisode() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Opportunities.Opened>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<Opportunities.Opened> open = () -> {
                    start.await();
                    return transactions.execute(status -> opportunities.open(scopeA,
                            OpportunityCategory.INVENTORY_RISK, Opportunity.PRODUCT_VARIANT, serum));
                };
                results.add(pool.submit(open));
            }
            start.countDown();
            List<Opportunities.Opened> opened = new ArrayList<>();
            for (Future<Opportunities.Opened> result : results) {
                opened.add(result.get());
            }
            assertThat(opened).filteredOn(Opportunities.Opened::created).hasSize(1);
            assertThat(opened).extracting(item -> item.opportunity().id()).containsOnly(opened.getFirst()
                    .opportunity().id());
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("opportunity")).isEqualTo(1);

        // A closed episode no longer blocks a new one for the same subject.
        Opportunity active = opportunities.active(scopeA, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, serum).orElseThrow();
        Opportunity closed = opportunities.transition(scopeA, active.id(), OpportunityStatus.CLOSED, "TEST");
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.closeReason()).isEqualTo("TEST");
        Opportunities.Opened next = opportunities.open(scopeA, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, serum);
        assertThat(next.created()).isTrue();
        assertThat(next.opportunity().id()).isNotEqualTo(active.id());
    }

    @Test
    void stateMachineRejectsIllegalAndStaleTransitionsAndOtherStores() {
        Opportunity opened = opportunities.open(scopeA, OpportunityCategory.INVENTORY_RISK,
                Opportunity.PRODUCT_VARIANT, serum).opportunity();

        assertThatThrownBy(() -> opportunities.transition(scopeA, opened.id(), OpportunityStatus.ACTING, null))
                .isInstanceOf(IllegalTransitionException.class);
        Opportunity analyzing = opportunities.transition(scopeA, opened.id(), OpportunityStatus.ANALYZING, null);
        assertThat(analyzing.version()).isEqualTo(opened.version() + 1);
        assertThat(analyzing.closedAt()).isNull();

        // Store B neither sees nor changes store A's episode.
        assertThat(opportunities.find(scopeB, opened.id())).isEmpty();
        assertThat(opportunities.active(scopeB, OpportunityCategory.INVENTORY_RISK, Opportunity.PRODUCT_VARIANT,
                serum)).isEmpty();
        assertThatThrownBy(() -> opportunities.transition(scopeB, opened.id(), OpportunityStatus.CLOSED, null))
                .isInstanceOf(IllegalArgumentException.class);

        opportunities.transition(scopeA, opened.id(), OpportunityStatus.RESOLVED, "STOCK_REPLENISHED");
        assertThatThrownBy(() -> opportunities.transition(scopeA, opened.id(), OpportunityStatus.ANALYZING, null))
                .isInstanceOf(IllegalTransitionException.class);
        // The database rejects a closed status without a closing time.
        assertThatThrownBy(() -> jdbc.sql("UPDATE opportunity SET closed_at = NULL WHERE id = ?").param(opened.id())
                .update()).hasMessageContaining("opportunity");
    }

    private void stock(int quantity, Instant observedAt) {
        inventory.record(scopeA, serum, new InventoryReading(quantity, StockStatus.IN_STOCK,
                ReservationSemantics.ALREADY_ACCOUNTED_FOR, null, ObservationSource.POLL, observedAt, observedAt));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE store_id = ?").param(STORE_A).query(Long.class)
                .single();
    }

    static final class TestClock extends Clock {

        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
