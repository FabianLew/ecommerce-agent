package com.ecommerceagent.commerce;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Products and variants of one store (ADR-0005): every method takes the trusted scope and every query
 * filters by its organization and store. Upserts never move a record backwards in source time.
 */
@Repository
public class CatalogRepository {

    private static final String VARIANT_COLUMNS = """
            v.id, v.product_id, v.source, v.external_id, p.name AS product_name, v.name, v.sku, v.status,
            v.stock_tracking, v.backorders
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    CatalogRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public UUID upsertProduct(TrustedStoreScope scope, CommerceSource source, ProductData product) {
        jdbc.sql("""
                        INSERT INTO product (id, organization_id, store_id, source, external_id, name, sku, product_type,
                                             status, source_updated_at, ingested_at)
                        VALUES (:id, :organizationId, :storeId, :source, :externalId, :name, :sku, :productType,
                                :status, :sourceUpdatedAt, :ingestedAt)
                        ON CONFLICT (store_id, source, external_id) DO UPDATE SET
                            name = EXCLUDED.name, sku = EXCLUDED.sku, product_type = EXCLUDED.product_type,
                            status = EXCLUDED.status, source_updated_at = EXCLUDED.source_updated_at,
                            ingested_at = EXCLUDED.ingested_at
                        WHERE product.source_updated_at IS NULL OR EXCLUDED.source_updated_at IS NULL
                           OR EXCLUDED.source_updated_at >= product.source_updated_at
                        """)
                .param("id", UUID.randomUUID())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("source", source.name())
                .param("externalId", product.externalId())
                .param("name", product.name())
                .param("sku", product.sku())
                .param("productType", product.productType())
                .param("status", product.status())
                .param("sourceUpdatedAt", timestamp(product.sourceUpdatedAt()))
                .param("ingestedAt", timestamp(clock.instant()))
                .update();
        return productId(scope, source, product.externalId()).orElseThrow();
    }

    @Transactional
    public UUID upsertVariant(TrustedStoreScope scope, CommerceSource source, VariantData variant) {
        UUID productId = productId(scope, source, variant.productExternalId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown product " + variant.productExternalId() + " for variant " + variant.externalId()));
        jdbc.sql("""
                        INSERT INTO product_variant (id, organization_id, store_id, product_id, source, external_id, name,
                                                     sku, status, stock_tracking, backorders, source_updated_at,
                                                     ingested_at)
                        VALUES (:id, :organizationId, :storeId, :productId, :source, :externalId, :name, :sku, :status,
                                :stockTracking, :backorders, :sourceUpdatedAt, :ingestedAt)
                        ON CONFLICT (store_id, source, external_id) DO UPDATE SET
                            product_id = EXCLUDED.product_id, name = EXCLUDED.name, sku = EXCLUDED.sku,
                            status = EXCLUDED.status, stock_tracking = EXCLUDED.stock_tracking,
                            backorders = EXCLUDED.backorders, source_updated_at = EXCLUDED.source_updated_at,
                            ingested_at = EXCLUDED.ingested_at
                        WHERE product_variant.source_updated_at IS NULL OR EXCLUDED.source_updated_at IS NULL
                           OR EXCLUDED.source_updated_at >= product_variant.source_updated_at
                        """)
                .param("id", UUID.randomUUID())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("productId", productId)
                .param("source", source.name())
                .param("externalId", variant.externalId())
                .param("name", variant.name())
                .param("sku", variant.sku())
                .param("status", variant.status())
                .param("stockTracking", variant.stockTracking().name())
                .param("backorders", variant.backorders().name())
                .param("sourceUpdatedAt", timestamp(variant.sourceUpdatedAt()))
                .param("ingestedAt", timestamp(clock.instant()))
                .update();
        return variantByExternalId(scope, source, variant.externalId()).orElseThrow().id();
    }

    public List<Variant> variants(TrustedStoreScope scope) {
        return jdbc.sql("SELECT " + VARIANT_COLUMNS + """
                        FROM product_variant v
                        JOIN product p ON p.id = v.product_id AND p.store_id = v.store_id
                        WHERE v.organization_id = :organizationId AND v.store_id = :storeId
                        ORDER BY p.name, v.name
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(CatalogRepository::mapVariant)
                .list();
    }

    public Optional<Variant> variant(TrustedStoreScope scope, UUID variantId) {
        return jdbc.sql("SELECT " + VARIANT_COLUMNS + """
                        FROM product_variant v
                        JOIN product p ON p.id = v.product_id AND p.store_id = v.store_id
                        WHERE v.id = :variantId AND v.organization_id = :organizationId AND v.store_id = :storeId
                        """)
                .param("variantId", variantId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(CatalogRepository::mapVariant)
                .optional();
    }

    /** Variants of one product, e.g. those sharing the parent's stock. */
    public List<Variant> variantsOfProduct(TrustedStoreScope scope, UUID productId) {
        return jdbc.sql("SELECT " + VARIANT_COLUMNS + """
                        FROM product_variant v
                        JOIN product p ON p.id = v.product_id AND p.store_id = v.store_id
                        WHERE v.product_id = :productId AND v.organization_id = :organizationId
                          AND v.store_id = :storeId
                        ORDER BY v.external_id
                        """)
                .param("productId", productId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(CatalogRepository::mapVariant)
                .list();
    }

    /** Source id of one of this store's products. */
    public Optional<String> productExternalId(TrustedStoreScope scope, UUID productId) {
        return jdbc.sql("""
                        SELECT external_id FROM product
                        WHERE id = :productId AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("productId", productId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(String.class)
                .optional();
    }

    /** Our id of a source product, if it was imported. */
    public Optional<UUID> productIdByExternalId(TrustedStoreScope scope, CommerceSource source, String externalId) {
        return productId(scope, source, externalId);
    }

    public Optional<Variant> variantByExternalId(TrustedStoreScope scope, CommerceSource source, String externalId) {
        return jdbc.sql("SELECT " + VARIANT_COLUMNS + """
                        FROM product_variant v
                        JOIN product p ON p.id = v.product_id AND p.store_id = v.store_id
                        WHERE v.source = :source AND v.external_id = :externalId
                          AND v.organization_id = :organizationId AND v.store_id = :storeId
                        """)
                .param("source", source.name())
                .param("externalId", externalId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(CatalogRepository::mapVariant)
                .optional();
    }

    public long productCount(TrustedStoreScope scope) {
        return jdbc.sql("SELECT count(*) FROM product WHERE organization_id = :organizationId AND store_id = :storeId")
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(Long.class)
                .single();
    }

    private Optional<UUID> productId(TrustedStoreScope scope, CommerceSource source, String externalId) {
        return jdbc.sql("""
                        SELECT id FROM product
                        WHERE source = :source AND external_id = :externalId
                          AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("source", source.name())
                .param("externalId", externalId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(UUID.class)
                .optional();
    }

    private static Variant mapVariant(ResultSet rs, int rowNum) throws SQLException {
        return new Variant(
                rs.getObject("id", UUID.class),
                rs.getObject("product_id", UUID.class),
                CommerceSource.valueOf(rs.getString("source")),
                rs.getString("external_id"),
                rs.getString("product_name"),
                rs.getString("name"),
                rs.getString("sku"),
                rs.getString("status"),
                StockTracking.valueOf(rs.getString("stock_tracking")),
                Backorders.valueOf(rs.getString("backorders")));
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
