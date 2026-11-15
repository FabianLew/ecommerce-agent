package com.ecommerceagent.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.OrderCoverage;
import com.ecommerceagent.commerce.OrderRepository;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.MetricValue.MetricPeriod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Sales and product metrics v1 (ADR-0019): net sales, qualifying orders, AOV and variant net sales over local dates,
 * computed only on the part of the window that the imported order history covers.
 */
@Service
public class SalesMetrics {

    public static final int MAX_DAYS = 366;

    private final OrderRepository orders;
    private final JdbcClient jdbc;

    SalesMetrics(OrderRepository orders, JdbcClient jdbc) {
        this.orders = orders;
        this.jdbc = jdbc;
    }

    /** A window of local dates, inclusive, in the store timezone. */
    public record LocalWindow(LocalDate from, LocalDate to, ZoneId zone) {

        public LocalWindow {
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("to is before from");
            }
            if (from.plusDays(MAX_DAYS - 1L).isBefore(to)) {
                throw new IllegalArgumentException("at most " + MAX_DAYS + " days");
            }
        }

        public Instant start() {
            return from.atStartOfDay(zone).toInstant();
        }

        public Instant endExclusive() {
            return to.plusDays(1).atStartOfDay(zone).toInstant();
        }

        public long days() {
            return to.toEpochDay() - from.toEpochDay() + 1;
        }

        /** The previous window of the same length. */
        public LocalWindow previous() {
            return new LocalWindow(from.minusDays(days()), from.minusDays(1), zone);
        }
    }

    /** The covered part of a window; empty when the history does not reach it. */
    public record Covered(Instant from, Instant toExclusive, boolean whole) {
    }

    public record DailySales(LocalDate day, BigDecimal netSales, Long orders) {
    }

    public record SalesSummary(String currency, LocalWindow window, Covered covered, MetricValue netSales,
            MetricValue orders, MetricValue aov, List<DailySales> series, long otherCurrencyOrders) {
    }

    public record VariantSales(UUID variantId, long units, BigDecimal netSales) {
    }

    public SalesSummary sales(TrustedStoreScope scope, LocalWindow window, Instant asOf) {
        String currency = storeCurrency(scope);
        MetricPeriod period = new MetricPeriod(window.start(), window.endExclusive(), window.zone().getId(), asOf);
        Optional<Covered> covered = covered(scope, window);
        if (covered.isEmpty()) {
            MetricValue none = MetricValue.unavailable(MetricDefinition.NET_SALES, period,
                    MetricReason.SALES_HISTORY_INCOMPLETE);
            return new SalesSummary(currency, window, null, none,
                    MetricValue.unavailable(MetricDefinition.QUALIFYING_ORDERS, period,
                            MetricReason.SALES_HISTORY_INCOMPLETE),
                    MetricValue.unavailable(MetricDefinition.AOV, period, MetricReason.SALES_HISTORY_INCOMPLETE),
                    days(window, null, Map.of()), 0);
        }
        Covered range = covered.get();
        Map<LocalDate, DailySales> byDay = new HashMap<>();
        jdbc.sql("""
                        SELECT (o.placed_at AT TIME ZONE :tz)::date AS day, count(*) AS orders,
                               sum(o.total_amount - o.tax_amount - o.shipping_amount) AS net_sales
                        FROM customer_order o
                        WHERE o.organization_id = :organizationId AND o.store_id = :storeId
                          AND o.placed_at >= :from AND o.placed_at < :toExclusive
                          AND o.status IN (:statuses) AND o.currency = :currency
                        GROUP BY 1
                        """)
                .param("tz", window.zone().getId())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("from", Timestamp.from(range.from()))
                .param("toExclusive", Timestamp.from(range.toExclusive()))
                .param("statuses", MetricDefinition.QUALIFYING_ORDER_STATUSES_V1)
                .param("currency", currency)
                .query((rs, n) -> new DailySales(rs.getObject("day", Date.class).toLocalDate(),
                        rs.getBigDecimal("net_sales").setScale(MetricDefinition.MONEY_SCALE, RoundingMode.HALF_UP),
                        rs.getLong("orders")))
                .list()
                .forEach(point -> byDay.put(point.day(), point));
        long other = jdbc.sql("""
                        SELECT count(*) FROM customer_order o
                        WHERE o.organization_id = :organizationId AND o.store_id = :storeId
                          AND o.placed_at >= :from AND o.placed_at < :toExclusive
                          AND o.status IN (:statuses) AND o.currency <> :currency
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("from", Timestamp.from(range.from()))
                .param("toExclusive", Timestamp.from(range.toExclusive()))
                .param("statuses", MetricDefinition.QUALIFYING_ORDER_STATUSES_V1)
                .param("currency", currency)
                .query(Long.class)
                .single();

        BigDecimal net = byDay.values().stream().map(DailySales::netSales).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(MetricDefinition.MONEY_SCALE, RoundingMode.HALF_UP);
        long count = byDay.values().stream().mapToLong(DailySales::orders).sum();
        List<MetricReason> common = new ArrayList<>();
        if (!range.whole()) {
            common.add(MetricReason.SALES_HISTORY_PARTIAL);
        }
        if (other > 0) {
            common.add(MetricReason.OTHER_CURRENCY_EXCLUDED);
        }
        List<MetricReason> netLimitations = new ArrayList<>(common);
        netLimitations.add(MetricReason.REFUNDS_NOT_INCLUDED);
        MetricValue netSales = MetricValue.of(MetricDefinition.NET_SALES, net, MetricQuality.ESTIMATED, period,
                netLimitations);
        MetricValue orderCount = MetricValue.of(MetricDefinition.QUALIFYING_ORDERS, BigDecimal.valueOf(count),
                common.isEmpty() ? MetricQuality.CONFIRMED : MetricQuality.ESTIMATED, period, common);
        MetricValue aov = count == 0
                ? MetricValue.unavailable(MetricDefinition.AOV, period, MetricReason.NO_ORDERS_IN_WINDOW)
                : MetricValue.of(MetricDefinition.AOV, net.divide(BigDecimal.valueOf(count),
                        MetricDefinition.MONEY_SCALE, RoundingMode.HALF_UP), MetricQuality.ESTIMATED, period,
                        netLimitations);
        return new SalesSummary(currency, window, range, netSales, orderCount, aov, days(window, range, byDay),
                other);
    }

    /** Units and line net sales per variant over the covered part of the window. */
    public Map<UUID, VariantSales> variantSales(TrustedStoreScope scope, LocalWindow window) {
        Optional<Covered> covered = covered(scope, window);
        if (covered.isEmpty()) {
            return Map.of();
        }
        Map<UUID, VariantSales> result = new HashMap<>();
        jdbc.sql("""
                        SELECT l.variant_id, coalesce(sum(l.quantity), 0) AS units,
                               coalesce(sum(l.line_total_amount), 0) AS net_sales
                        FROM order_line l
                        JOIN customer_order o ON o.id = l.order_id AND o.store_id = l.store_id
                        WHERE o.organization_id = :organizationId AND o.store_id = :storeId
                          AND l.variant_id IS NOT NULL
                          AND o.placed_at >= :from AND o.placed_at < :toExclusive
                          AND o.status IN (:statuses) AND o.currency = :currency
                        GROUP BY l.variant_id
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("from", Timestamp.from(covered.get().from()))
                .param("toExclusive", Timestamp.from(covered.get().toExclusive()))
                .param("statuses", MetricDefinition.QUALIFYING_ORDER_STATUSES_V1)
                .param("currency", storeCurrency(scope))
                .query((rs, n) -> new VariantSales(rs.getObject("variant_id", UUID.class), rs.getLong("units"),
                        rs.getBigDecimal("net_sales").setScale(MetricDefinition.MONEY_SCALE, RoundingMode.HALF_UP)))
                .list()
                .forEach(row -> result.put(row.variantId(), row));
        return result;
    }

    /** Daily units of one variant; an uncovered day has null units. */
    public List<DailySales> variantDaily(TrustedStoreScope scope, UUID variantId, LocalWindow window) {
        Optional<Covered> covered = covered(scope, window);
        if (covered.isEmpty()) {
            return days(window, null, Map.of());
        }
        Map<LocalDate, DailySales> byDay = new HashMap<>();
        jdbc.sql("""
                        SELECT (o.placed_at AT TIME ZONE :tz)::date AS day, coalesce(sum(l.quantity), 0) AS units,
                               coalesce(sum(l.line_total_amount), 0) AS net_sales
                        FROM order_line l
                        JOIN customer_order o ON o.id = l.order_id AND o.store_id = l.store_id
                        WHERE o.organization_id = :organizationId AND o.store_id = :storeId AND l.variant_id = :variantId
                          AND o.placed_at >= :from AND o.placed_at < :toExclusive
                          AND o.status IN (:statuses) AND o.currency = :currency
                        GROUP BY 1
                        """)
                .param("tz", window.zone().getId())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("variantId", variantId)
                .param("from", Timestamp.from(covered.get().from()))
                .param("toExclusive", Timestamp.from(covered.get().toExclusive()))
                .param("statuses", MetricDefinition.QUALIFYING_ORDER_STATUSES_V1)
                .param("currency", storeCurrency(scope))
                .query((rs, n) -> new DailySales(rs.getObject("day", Date.class).toLocalDate(),
                        rs.getBigDecimal("net_sales").setScale(MetricDefinition.MONEY_SCALE, RoundingMode.HALF_UP),
                        rs.getLong("units")))
                .list()
                .forEach(point -> byDay.put(point.day(), point));
        return days(window, covered.get(), byDay);
    }

    public Optional<Covered> covered(TrustedStoreScope scope, LocalWindow window) {
        Optional<OrderCoverage> coverage = orders.coverage(scope, CommerceSource.WOOCOMMERCE);
        if (coverage.isEmpty()) {
            return Optional.empty();
        }
        Instant from = max(window.start(), coverage.get().completeFrom());
        Instant to = min(window.endExclusive(), coverage.get().completeUntil());
        if (!from.isBefore(to)) {
            return Optional.empty();
        }
        return Optional.of(new Covered(from, to, from.equals(window.start()) && to.equals(window.endExclusive())));
    }

    public ZoneId zone(TrustedStoreScope scope) {
        return ZoneId.of(jdbc.sql("SELECT timezone FROM store WHERE id = :storeId AND organization_id = :organizationId")
                .param("storeId", scope.storeId())
                .param("organizationId", scope.organizationId())
                .query(String.class)
                .single());
    }

    private String storeCurrency(TrustedStoreScope scope) {
        return jdbc.sql("SELECT currency FROM store WHERE id = :storeId AND organization_id = :organizationId")
                .param("storeId", scope.storeId())
                .param("organizationId", scope.organizationId())
                .query(String.class)
                .single();
    }

    /** One point per local day; a day not fully inside the covered range has null values. */
    private static List<DailySales> days(LocalWindow window, Covered covered, Map<LocalDate, DailySales> byDay) {
        List<DailySales> points = new ArrayList<>();
        for (LocalDate day = window.from(); !day.isAfter(window.to()); day = day.plusDays(1)) {
            Instant start = day.atStartOfDay(window.zone()).toInstant();
            Instant end = day.plusDays(1).atStartOfDay(window.zone()).toInstant();
            boolean inside = covered != null && !start.isBefore(covered.from()) && !end.isAfter(covered.toExclusive());
            DailySales point = byDay.get(day);
            points.add(!inside ? new DailySales(day, null, null)
                    : point != null ? point
                    : new DailySales(day, BigDecimal.ZERO.setScale(MetricDefinition.MONEY_SCALE), 0L));
        }
        return List.copyOf(points);
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
