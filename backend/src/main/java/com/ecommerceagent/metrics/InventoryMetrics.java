package com.ecommerceagent.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.commerce.Backorders;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.InventoryObservation;
import com.ecommerceagent.commerce.InventoryRepository;
import com.ecommerceagent.commerce.OrderCoverage;
import com.ecommerceagent.commerce.OrderRepository;
import com.ecommerceagent.commerce.StockStatus;
import com.ecommerceagent.commerce.StockTracking;
import com.ecommerceagent.commerce.Variant;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.MetricValue.MetricPeriod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Metrics Engine v1 for Inventory Guardian (B-17): UNITS_SOLD, SALES_VELOCITY_7_FULL_LOCAL_DAYS and
 * DAYS_OF_COVER, deterministic and without LLM. Missing inputs give {@code null} with a reason, never zero.
 */
@Service
public class InventoryMetrics {

    private final CatalogRepository catalog;
    private final InventoryRepository inventory;
    private final OrderRepository orders;
    private final JdbcClient jdbc;

    InventoryMetrics(CatalogRepository catalog, InventoryRepository inventory, OrderRepository orders,
            JdbcClient jdbc) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.orders = orders;
        this.jdbc = jdbc;
    }

    /** The 7 full local days before the day of {@code asOf} in the store timezone. */
    public static MetricPeriod velocityWindow(Instant asOf, ZoneId zone) {
        LocalDate today = LocalDate.ofInstant(asOf, zone);
        Instant from = today.minusDays(MetricDefinition.VELOCITY_WINDOW_DAYS).atStartOfDay(zone).toInstant();
        Instant to = today.atStartOfDay(zone).toInstant();
        return new MetricPeriod(from, to, zone.getId(), asOf);
    }

    public InventoryCoverage coverage(TrustedStoreScope scope, UUID variantId, Instant asOf) {
        Variant variant = catalog.variant(scope, variantId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown variant " + variantId));
        MetricPeriod window = velocityWindow(asOf, ZoneId.of(storeTimezone(scope)));
        MetricPeriod moment = new MetricPeriod(asOf, asOf, window.timezone(), asOf);
        Optional<InventoryObservation> stock = inventory.latestAt(scope, variantId, asOf);

        if (!salesHistoryComplete(scope, variant.source(), window)) {
            return new InventoryCoverage(variantId, asOf,
                    MetricValue.unavailable(MetricDefinition.UNITS_SOLD, window, MetricReason.SALES_HISTORY_INCOMPLETE),
                    MetricValue.unavailable(MetricDefinition.SALES_VELOCITY_7_FULL_LOCAL_DAYS, window,
                            MetricReason.SALES_HISTORY_INCOMPLETE),
                    MetricValue.unavailable(MetricDefinition.DAYS_OF_COVER, moment, MetricReason.SALES_HISTORY_INCOMPLETE),
                    stock.orElse(null));
        }

        long units = orders.unitsSold(scope, variantId, window.from(), window.toExclusive(),
                MetricDefinition.QUALIFYING_ORDER_STATUSES_V1);
        List<MetricReason> demandLimitations = demandLimitations(scope, variantId, window);
        MetricValue unitsSold = MetricValue.of(MetricDefinition.UNITS_SOLD, BigDecimal.valueOf(units),
                MetricQuality.CONFIRMED, window, List.of());
        // Past sales are a proxy for demand: the velocity is an estimate, limited further by stockouts (IG-25).
        MetricValue velocity = MetricValue.of(MetricDefinition.SALES_VELOCITY_7_FULL_LOCAL_DAYS,
                BigDecimal.valueOf(units).divide(BigDecimal.valueOf(MetricDefinition.VELOCITY_WINDOW_DAYS),
                        MetricDefinition.SCALE, RoundingMode.HALF_UP),
                MetricQuality.ESTIMATED, window, demandLimitations);
        return new InventoryCoverage(variantId, asOf, unitsSold, velocity,
                daysOfCover(variant, stock, units, moment, demandLimitations), stock.orElse(null));
    }

    private MetricValue daysOfCover(Variant variant, Optional<InventoryObservation> stock, long units,
            MetricPeriod moment, List<MetricReason> demandLimitations) {
        MetricDefinition definition = MetricDefinition.DAYS_OF_COVER;
        if (variant.stockTracking() == StockTracking.NONE) {
            return MetricValue.unavailable(definition, moment, MetricReason.STOCK_NOT_TRACKED);
        }
        if (variant.stockTracking() == StockTracking.PARENT) {
            return MetricValue.unavailable(definition, moment, MetricReason.SHARED_STOCK_NOT_SUPPORTED);
        }
        if (stock.isEmpty()) {
            return MetricValue.unavailable(definition, moment, MetricReason.NO_STOCK_OBSERVATION);
        }
        Integer available = stock.get().reading().availableToSell();
        if (available == null) {
            return MetricValue.unavailable(definition, moment, MetricReason.AVAILABLE_STOCK_UNKNOWN);
        }
        if (units == 0) {
            return MetricValue.unavailable(definition, moment, MetricReason.NO_SALES_IN_WINDOW);
        }
        List<MetricReason> limitations = new ArrayList<>(demandLimitations);
        if (variant.backorders() != Backorders.NO) {
            limitations.add(MetricReason.BACKORDERS_ALLOWED);
        }
        // available / (units / days) computed as available * days / units, so the ratio is rounded only once.
        BigDecimal value = BigDecimal.valueOf(Math.max(available, 0))
                .multiply(BigDecimal.valueOf(MetricDefinition.VELOCITY_WINDOW_DAYS))
                .divide(BigDecimal.valueOf(units), MetricDefinition.SCALE, RoundingMode.HALF_UP);
        return MetricValue.of(definition, value, MetricQuality.ESTIMATED, moment, limitations);
    }

    private boolean salesHistoryComplete(TrustedStoreScope scope, CommerceSource source, MetricPeriod window) {
        return orders.coverage(scope, source)
                .map((OrderCoverage coverage) -> coverage.covers(window.from(), window.toExclusive()))
                .orElse(false);
    }

    private List<MetricReason> demandLimitations(TrustedStoreScope scope, UUID variantId, MetricPeriod window) {
        List<InventoryObservation> readings = inventory.observed(scope, variantId, window.from(), window.toExclusive());
        if (readings.isEmpty()) {
            return List.of(MetricReason.STOCK_HISTORY_UNVERIFIED);
        }
        boolean stockout = readings.stream().anyMatch(o -> o.reading().stockStatus() == StockStatus.OUT_OF_STOCK
                || (o.reading().availableToSell() != null && o.reading().availableToSell() <= 0));
        return stockout ? List.of(MetricReason.STOCKOUT_IN_WINDOW) : List.of();
    }

    private String storeTimezone(TrustedStoreScope scope) {
        return jdbc.sql("SELECT timezone FROM store WHERE id = :storeId AND organization_id = :organizationId")
                .param("storeId", scope.storeId())
                .param("organizationId", scope.organizationId())
                .query(String.class)
                .single();
    }
}
