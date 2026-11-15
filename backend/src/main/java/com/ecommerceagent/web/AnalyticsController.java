package com.ecommerceagent.web;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.commerce.InventoryObservation;
import com.ecommerceagent.commerce.MappedAd;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.SalesMetrics;
import com.ecommerceagent.metrics.SalesMetrics.Covered;
import com.ecommerceagent.metrics.SalesMetrics.DailySales;
import com.ecommerceagent.metrics.SalesMetrics.LocalWindow;
import com.ecommerceagent.metrics.SalesMetrics.SalesSummary;
import com.ecommerceagent.reporting.ProductAnalytics;
import com.ecommerceagent.reporting.ProductAnalytics.ProductPage;
import com.ecommerceagent.reporting.ProductAnalytics.ProductRow;
import com.ecommerceagent.reporting.ProductAnalytics.VariantDetail;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sales and Products analytics (04 §7, ADR-0019). Read only; nothing here runs a model. */
@RestController
@RequestMapping("/api/v1/stores/{storeId}/analytics")
class AnalyticsController {

    static final String ORDERS_SOURCE = "woocommerce:orders";

    private final SalesMetrics sales;
    private final ProductAnalytics products;
    private final Clock clock;

    AnalyticsController(SalesMetrics sales, ProductAnalytics products, Clock clock) {
        this.sales = sales;
        this.products = products;
        this.clock = clock;
    }

    enum Compare {
        PREVIOUS_PERIOD,
        NONE
    }

    @GetMapping("/sales")
    SalesResponse sales(TrustedStoreScope scope, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(defaultValue = "PREVIOUS_PERIOD") String compare) {
        Compare comparison = choice(Compare.class, compare);
        LocalWindow window = window(scope, from, to);
        Instant now = clock.instant();
        SalesSummary current = sales.sales(scope, window, now);
        SalesSummary base = comparison == Compare.PREVIOUS_PERIOD ? sales.sales(scope, window.previous(), now) : null;
        List<String> refs = List.of(ORDERS_SOURCE);
        String currency = current.currency();
        return new SalesResponse(meta(window, current.covered(), currency, now),
                List.of(MetricDto.of(current.netSales(), currency, refs, base == null ? null : base.netSales()),
                        MetricDto.of(current.orders(), currency, refs, base == null ? null : base.orders()),
                        MetricDto.of(current.aov(), currency, refs, base == null ? null : base.aov())),
                current.series().stream().map(SeriesPoint::from).toList(), current.otherCurrencyOrders());
    }

    @GetMapping("/products")
    ProductsResponse products(TrustedStoreScope scope, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "NET_SALES") String sort,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        if (offset < 0 || limit < 1 || limit > ProductAnalytics.MAX_PAGE) {
            throw new InvalidQueryException("INVALID_PAGE");
        }
        LocalWindow window = window(scope, from, to);
        Instant now = clock.instant();
        SalesSummary summary = sales.sales(scope, window, now);
        ProductPage page = products.products(scope, window, choice(ProductAnalytics.Sort.class, sort), offset, limit, summary.currency());
        return new ProductsResponse(meta(window, page.covered(), page.currency(), now),
                page.rows().stream().map(row -> ProductRowResponse.from(row, page.covered() != null)).toList(),
                page.total(), page.offset(), page.offset() + page.rows().size() < page.total());
    }

    @GetMapping("/products/{variantId}")
    ProductDetailResponse product(TrustedStoreScope scope, @PathVariable String variantId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        UUID id;
        try {
            id = UUID.fromString(variantId);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException();
        }
        LocalWindow window = window(scope, from, to);
        Instant now = clock.instant();
        String currency = sales.sales(scope, window, now).currency();
        VariantDetail detail = products.variant(scope, id, window).orElseThrow(ResourceNotFoundException::new);
        return new ProductDetailResponse(meta(window, detail.covered(), currency, now),
                ProductRowResponse.variant(detail.variant()),
                MetricDto.of(detail.coverage().daysOfCover(), currency, List.of(ORDERS_SOURCE), null),
                MetricDto.of(detail.coverage().velocityPerDay(), currency, List.of(ORDERS_SOURCE), null),
                detail.coverage().stock() == null ? null : detail.coverage().stock().reading().availableToSell(),
                detail.daily().stream().map(SeriesPoint::from).toList(),
                detail.stock().stream().map(StockPoint::from).toList(),
                detail.ads().stream().map(LinkedAd::from).toList(),
                detail.decisions().stream().map(d -> new LinkedDecision(d.opportunityId(), d.status(), d.openedAt(),
                        d.latestProposalId())).toList());
    }

    private static <E extends Enum<E>> E choice(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException("INVALID_FILTER");
        }
    }

    private LocalWindow window(TrustedStoreScope scope, String from, String to) {
        ZoneId zone = sales.zone(scope);
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        try {
            // By default the last 30 full local days, so the comparison is between whole days.
            LocalDate end = to == null ? today.minusDays(1) : LocalDate.parse(to);
            LocalDate start = from == null ? end.minusDays(29) : LocalDate.parse(from);
            if (end.isAfter(today.plusDays(1))) {
                throw new InvalidQueryException("INVALID_PERIOD");
            }
            return new LocalWindow(start, end, zone);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new InvalidQueryException("INVALID_PERIOD");
        }
    }

    private static AnalyticsMeta meta(LocalWindow window, Covered covered, String currency, Instant now) {
        return new AnalyticsMeta(window.from(), window.to(), window.zone().getId(), currency,
                covered == null ? null : covered.from(), covered == null ? null : covered.toExclusive(),
                covered != null && covered.whole(), now);
    }

    record AnalyticsMeta(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate from,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate to,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String timezone,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
            Instant coveredFrom,
            Instant coveredUntil,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean fullyCovered,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant computedAt) {
    }

    record SeriesPoint(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate day,
            @Schema(description = "Null when the day is outside the imported history") String netSales,
            @Schema(description = "Orders (sales) or units (product); null when not covered") Long count) {

        static SeriesPoint from(DailySales point) {
            return new SeriesPoint(point.day(), MetricDto.text(point.netSales()), point.orders());
        }
    }

    record SalesResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AnalyticsMeta meta,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MetricDto> metrics,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SeriesPoint> series,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long otherCurrencyOrders) {
    }

    record VariantRef(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            String sku,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String productName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {
    }

    record ProductRowResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) VariantRef variant,
            @Schema(description = "Null when the period is not covered") Long units,
            String netSales,
            Integer availableToSell,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MetricDto daysOfCover) {

        static VariantRef variant(com.ecommerceagent.commerce.Variant variant) {
            return new VariantRef(variant.id(), variant.sku(), variant.productName(), variant.name());
        }

        static ProductRowResponse from(ProductRow row, boolean covered) {
            InventoryObservation stock = row.coverage().stock();
            return new ProductRowResponse(variant(row.variant()), covered ? row.units() : null,
                    covered ? MetricDto.text(row.netSales()) : null,
                    stock == null ? null : stock.reading().availableToSell(),
                    MetricDto.of(row.coverage().daysOfCover(), null, List.of(ORDERS_SOURCE), null));
        }
    }

    record ProductsResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AnalyticsMeta meta,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ProductRowResponse> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int total,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int offset,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) {
    }

    record StockPoint(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant observedAt,
            Integer availableToSell,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String stockStatus) {

        static StockPoint from(InventoryObservation observation) {
            return new StockPoint(observation.reading().observedAt(), observation.reading().availableToSell(),
                    observation.reading().stockStatus().name());
        }
    }

    record LinkedAd(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID adId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String externalId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ownStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mappingScope,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean verifiedForCurrentCreative) {

        static LinkedAd from(MappedAd mapped) {
            return new LinkedAd(mapped.ad().id(), mapped.ad().externalId(), mapped.ad().name(),
                    mapped.ad().ownStatus().name(), mapped.mapping().scope().name(),
                    mapped.mapping().currentFor(mapped.ad()));
        }
    }

    record LinkedDecision(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID opportunityId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant openedAt,
            UUID latestProposalId) {
    }

    record ProductDetailResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AnalyticsMeta meta,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) VariantRef variant,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MetricDto daysOfCover,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MetricDto salesVelocity,
            Integer availableToSell,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SeriesPoint> daily,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<StockPoint> stock,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LinkedAd> ads,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LinkedDecision> decisions) {
    }
}
