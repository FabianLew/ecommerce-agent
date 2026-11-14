package com.ecommerceagent.commerce;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Orders of one store without personal data, and the period for which they are known to be complete. */
@Repository
public class OrderRepository {

    private final JdbcClient jdbc;
    private final Clock clock;

    OrderRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Inserts or updates an order and replaces its lines. An older source version never overwrites a newer
     * one. Returns false when the order was left unchanged for that reason.
     */
    @Transactional
    public boolean upsert(TrustedStoreScope scope, CommerceSource source, OrderData order) {
        Optional<UUID> written = jdbc.sql("""
                        INSERT INTO customer_order (id, organization_id, store_id, source, external_id, status, currency,
                                                    total_amount, tax_amount, discount_amount, shipping_amount,
                                                    placed_at, paid_at, completed_at, source_updated_at, ingested_at)
                        VALUES (:id, :organizationId, :storeId, :source, :externalId, :status, :currency, :total, :tax,
                                :discount, :shipping, :placedAt, :paidAt, :completedAt, :sourceUpdatedAt, :ingestedAt)
                        ON CONFLICT (store_id, source, external_id) DO UPDATE SET
                            status = EXCLUDED.status, currency = EXCLUDED.currency,
                            total_amount = EXCLUDED.total_amount, tax_amount = EXCLUDED.tax_amount,
                            discount_amount = EXCLUDED.discount_amount, shipping_amount = EXCLUDED.shipping_amount,
                            placed_at = EXCLUDED.placed_at, paid_at = EXCLUDED.paid_at,
                            completed_at = EXCLUDED.completed_at, source_updated_at = EXCLUDED.source_updated_at,
                            ingested_at = EXCLUDED.ingested_at
                        WHERE customer_order.source_updated_at IS NULL OR EXCLUDED.source_updated_at IS NULL
                           OR EXCLUDED.source_updated_at >= customer_order.source_updated_at
                        RETURNING id
                        """)
                .param("id", UUID.randomUUID())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("source", source.name())
                .param("externalId", order.externalId())
                .param("status", order.status())
                .param("currency", order.currency())
                .param("total", order.totalAmount())
                .param("tax", order.taxAmount())
                .param("discount", order.discountAmount())
                .param("shipping", order.shippingAmount())
                .param("placedAt", Timestamp.from(order.placedAt()))
                .param("paidAt", CatalogRepository.timestamp(order.paidAt()))
                .param("completedAt", CatalogRepository.timestamp(order.completedAt()))
                .param("sourceUpdatedAt", CatalogRepository.timestamp(order.sourceUpdatedAt()))
                .param("ingestedAt", Timestamp.from(clock.instant()))
                .query(UUID.class)
                .optional();
        if (written.isEmpty()) {
            return false;
        }
        UUID orderId = written.get();
        jdbc.sql("DELETE FROM order_line WHERE order_id = :orderId AND store_id = :storeId")
                .param("orderId", orderId)
                .param("storeId", scope.storeId())
                .update();
        for (OrderLineData line : order.lines()) {
            jdbc.sql("""
                            INSERT INTO order_line (id, organization_id, store_id, order_id, external_line_id, variant_id,
                                                    product_external_id, sku, quantity, line_total_amount,
                                                    line_tax_amount)
                            VALUES (:id, :organizationId, :storeId, :orderId, :externalLineId,
                                    (SELECT v.id FROM product_variant v
                                     WHERE v.source = :source AND v.external_id = :variantExternalId
                                       AND v.organization_id = :organizationId AND v.store_id = :storeId),
                                    :productExternalId, :sku, :quantity, :lineTotal, :lineTax)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("organizationId", scope.organizationId())
                    .param("storeId", scope.storeId())
                    .param("orderId", orderId)
                    .param("externalLineId", line.externalLineId())
                    .param("source", source.name())
                    .param("variantExternalId", line.variantExternalId())
                    .param("productExternalId", line.productExternalId())
                    .param("sku", line.sku())
                    .param("quantity", line.quantity())
                    .param("lineTotal", line.lineTotalAmount())
                    .param("lineTax", line.lineTaxAmount())
                    .update();
        }
        return true;
    }

    /** Source modification time of a stored order, if we have it. */
    public Optional<Instant> sourceUpdatedAt(TrustedStoreScope scope, CommerceSource source, String externalId) {
        return jdbc.sql("""
                        SELECT source_updated_at FROM customer_order
                        WHERE source = :source AND external_id = :externalId
                          AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("source", source.name())
                .param("externalId", externalId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query((rs, n) -> Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant))
                .optional()
                .flatMap(value -> value);
    }

    /** Units of a variant in orders placed in [from, toExclusive) with one of the given statuses. */
    public long unitsSold(TrustedStoreScope scope, UUID variantId, Instant from, Instant toExclusive,
            Collection<String> statuses) {
        if (statuses.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        SELECT coalesce(sum(l.quantity), 0)
                        FROM order_line l
                        JOIN customer_order o ON o.id = l.order_id AND o.store_id = l.store_id
                        WHERE l.variant_id = :variantId
                          AND o.organization_id = :organizationId AND o.store_id = :storeId
                          AND o.placed_at >= :from AND o.placed_at < :toExclusive
                          AND o.status IN (:statuses)
                        """)
                .param("variantId", variantId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("from", Timestamp.from(from))
                .param("toExclusive", Timestamp.from(toExclusive))
                .param("statuses", statuses)
                .query(Long.class)
                .single();
    }

    public long orderCount(TrustedStoreScope scope, CommerceSource source, Instant placedFrom) {
        return jdbc.sql("""
                        SELECT count(*) FROM customer_order
                        WHERE organization_id = :organizationId AND store_id = :storeId AND source = :source
                          AND placed_at >= :placedFrom
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("source", source.name())
                .param("placedFrom", Timestamp.from(placedFrom))
                .query(Long.class)
                .single();
    }

    public Optional<OrderCoverage> coverage(TrustedStoreScope scope, CommerceSource source) {
        return jdbc.sql("""
                        SELECT complete_from, complete_until FROM order_sync_coverage
                        WHERE organization_id = :organizationId AND store_id = :storeId AND source = :source
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("source", source.name())
                .query((rs, rowNum) -> new OrderCoverage(source, rs.getTimestamp("complete_from").toInstant(),
                        rs.getTimestamp("complete_until").toInstant()))
                .optional();
    }

    /**
     * Records that all orders placed in [from, until) are synchronized. An overlapping period extends the
     * known coverage; a disjoint one replaces it, because coverage must stay a single gap-free period.
     */
    public void recordCoverage(TrustedStoreScope scope, CommerceSource source, Instant from, Instant until) {
        jdbc.sql("""
                        INSERT INTO order_sync_coverage (organization_id, store_id, source, complete_from, complete_until,
                                                         updated_at)
                        VALUES (:organizationId, :storeId, :source, :from, :until, :now)
                        ON CONFLICT (store_id, source) DO UPDATE SET
                            complete_from = CASE
                                WHEN EXCLUDED.complete_from <= order_sync_coverage.complete_until
                                 AND EXCLUDED.complete_until >= order_sync_coverage.complete_from
                                THEN least(order_sync_coverage.complete_from, EXCLUDED.complete_from)
                                ELSE EXCLUDED.complete_from END,
                            complete_until = CASE
                                WHEN EXCLUDED.complete_from <= order_sync_coverage.complete_until
                                 AND EXCLUDED.complete_until >= order_sync_coverage.complete_from
                                THEN greatest(order_sync_coverage.complete_until, EXCLUDED.complete_until)
                                ELSE EXCLUDED.complete_until END,
                            updated_at = EXCLUDED.updated_at
                        WHERE order_sync_coverage.organization_id = EXCLUDED.organization_id
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("source", source.name())
                .param("from", Timestamp.from(from))
                .param("until", Timestamp.from(until))
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    /**
     * Extends the start of the known coverage back to {@code from} when [from, until) touches it (ADR-0015). It never
     * creates or replaces coverage and never moves its end; returns whether the coverage changed.
     */
    public boolean extendCoverageBack(TrustedStoreScope scope, CommerceSource source, Instant from, Instant until) {
        return jdbc.sql("""
                        UPDATE order_sync_coverage SET complete_from = :from, updated_at = :now
                        WHERE organization_id = :organizationId AND store_id = :storeId AND source = :source
                          AND complete_from > :from AND complete_from <= :until
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("source", source.name())
                .param("from", Timestamp.from(from))
                .param("until", Timestamp.from(until))
                .param("now", Timestamp.from(clock.instant()))
                .update() > 0;
    }
}
