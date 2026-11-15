package com.ecommerceagent.reporting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.InventoryObservation;
import com.ecommerceagent.commerce.InventoryRepository;
import com.ecommerceagent.commerce.MappedAd;
import com.ecommerceagent.commerce.AdRepository;
import com.ecommerceagent.commerce.Variant;
import com.ecommerceagent.decisions.DecisionProposals;
import com.ecommerceagent.decisions.Opportunities;
import com.ecommerceagent.decisions.Opportunity;
import com.ecommerceagent.decisions.StoredProposal;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.InventoryCoverage;
import com.ecommerceagent.metrics.InventoryMetrics;
import com.ecommerceagent.metrics.SalesMetrics;
import com.ecommerceagent.metrics.SalesMetrics.DailySales;
import com.ecommerceagent.metrics.SalesMetrics.LocalWindow;
import com.ecommerceagent.metrics.SalesMetrics.VariantSales;
import org.springframework.stereotype.Service;

/** Products analytics (04 §7, ADR-0019): variants with sales, stock and cover; one variant's drill-down. */
@Service
public class ProductAnalytics {

    public static final int MAX_PAGE = 100;

    public enum Sort {
        NET_SALES,
        UNITS,
        DAYS_OF_COVER
    }

    public record ProductRow(Variant variant, long units, BigDecimal netSales, InventoryCoverage coverage) {
    }

    public record ProductPage(List<ProductRow> rows, int total, int offset, SalesMetrics.Covered covered,
            String currency) {
    }

    public record DecisionLink(UUID opportunityId, String status, Instant openedAt, UUID latestProposalId) {
    }

    public record VariantDetail(Variant variant, InventoryCoverage coverage, List<DailySales> daily,
            List<InventoryObservation> stock, List<MappedAd> ads, List<DecisionLink> decisions,
            SalesMetrics.Covered covered) {
    }

    private final CatalogRepository catalog;
    private final InventoryRepository inventory;
    private final AdRepository ads;
    private final SalesMetrics sales;
    private final InventoryMetrics inventoryMetrics;
    private final Opportunities opportunities;
    private final DecisionProposals proposals;
    private final Clock clock;

    ProductAnalytics(CatalogRepository catalog, InventoryRepository inventory, AdRepository ads, SalesMetrics sales,
            InventoryMetrics inventoryMetrics, Opportunities opportunities, DecisionProposals proposals, Clock clock) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.ads = ads;
        this.sales = sales;
        this.inventoryMetrics = inventoryMetrics;
        this.opportunities = opportunities;
        this.proposals = proposals;
        this.clock = clock;
    }

    public ProductPage products(TrustedStoreScope scope, LocalWindow window, Sort sort, int offset, int limit,
            String currency) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        Map<UUID, VariantSales> byVariant = sales.variantSales(scope, window);
        Comparator<Variant> order = switch (sort) {
            case UNITS -> Comparator.comparingLong((Variant v) -> units(byVariant, v)).reversed();
            case NET_SALES, DAYS_OF_COVER -> Comparator.comparing((Variant v) -> net(byVariant, v)).reversed();
        };
        List<Variant> variants = catalog.variants(scope).stream()
                .sorted(order.thenComparing(Variant::productName).thenComparing(Variant::name)).toList();
        Instant now = clock.instant();
        List<ProductRow> rows;
        if (sort == Sort.DAYS_OF_COVER) {
            // Lowest known cover first, unknown cover last; needs the cover of every variant (pilot catalogs).
            rows = variants.stream()
                    .map(v -> new ProductRow(v, units(byVariant, v), net(byVariant, v),
                            inventoryMetrics.coverage(scope, v.id(), now)))
                    .sorted(Comparator.comparing((ProductRow r) -> r.coverage().daysOfCover().value(),
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .skip(Math.max(0, offset)).limit(size).toList();
        } else {
            rows = variants.stream().skip(Math.max(0, offset)).limit(size)
                    .map(v -> new ProductRow(v, units(byVariant, v), net(byVariant, v),
                            inventoryMetrics.coverage(scope, v.id(), now)))
                    .toList();
        }
        return new ProductPage(rows, variants.size(), Math.max(0, offset), sales.covered(scope, window).orElse(null),
                currency);
    }

    public Optional<VariantDetail> variant(TrustedStoreScope scope, UUID variantId, LocalWindow window) {
        return catalog.variant(scope, variantId).map(variant -> {
            List<DecisionLink> decisions = opportunities.ofSubject(scope, Opportunity.PRODUCT_VARIANT, variantId, 10)
                    .stream()
                    .map(o -> new DecisionLink(o.id(), o.status().name(), o.openedAt(),
                            proposals.ofOpportunity(scope, o.id()).stream().reduce((a, b) -> b)
                                    .map((StoredProposal p) -> p.proposal().proposalId()).orElse(null)))
                    .toList();
            return new VariantDetail(variant, inventoryMetrics.coverage(scope, variantId, clock.instant()),
                    sales.variantDaily(scope, variantId, window),
                    inventory.observed(scope, variantId, window.start(), window.endExclusive()),
                    ads.adsForVariant(scope, variantId), decisions, sales.covered(scope, window).orElse(null));
        });
    }

    private static long units(Map<UUID, VariantSales> byVariant, Variant variant) {
        VariantSales row = byVariant.get(variant.id());
        return row == null ? 0 : row.units();
    }

    private static BigDecimal net(Map<UUID, VariantSales> byVariant, Variant variant) {
        VariantSales row = byVariant.get(variant.id());
        return row == null ? BigDecimal.ZERO.setScale(2) : row.netSales();
    }
}
