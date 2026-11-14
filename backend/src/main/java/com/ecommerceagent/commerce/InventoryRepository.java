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

/** Append-only stock readings of a store's variants (document 03 §3). */
@Repository
public class InventoryRepository {

    private static final String COLUMNS = """
            id, variant_id, stock_quantity, stock_status, reservation_semantics, reserved_quantity,
            observation_source, source_updated_at, observed_at, ingested_at
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    InventoryRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Records a reading for a variant of this store. A variant of another store is rejected by the
     * query itself (no row inserted), not only by the foreign key.
     */
    public UUID record(TrustedStoreScope scope, UUID variantId, InventoryReading reading) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        INSERT INTO inventory_observation (id, organization_id, store_id, variant_id, stock_quantity,
                                                           stock_status, reservation_semantics, reserved_quantity,
                                                           observation_source, source_updated_at, observed_at,
                                                           ingested_at)
                        SELECT :id, v.organization_id, v.store_id, v.id, :stockQuantity, :stockStatus, :semantics,
                               :reservedQuantity, :observationSource, :sourceUpdatedAt, :observedAt, :ingestedAt
                        FROM product_variant v
                        WHERE v.id = :variantId AND v.organization_id = :organizationId AND v.store_id = :storeId
                        """)
                .param("id", id)
                .param("stockQuantity", reading.stockQuantity())
                .param("stockStatus", reading.stockStatus().name())
                .param("semantics", reading.semantics().name())
                .param("reservedQuantity", reading.reservedQuantity())
                .param("observationSource", reading.observationSource().name())
                .param("sourceUpdatedAt", CatalogRepository.timestamp(reading.sourceUpdatedAt()))
                .param("observedAt", Timestamp.from(reading.observedAt()))
                .param("ingestedAt", Timestamp.from(clock.instant()))
                .param("variantId", variantId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
        if (inserted != 1) {
            throw new IllegalArgumentException("Variant " + variantId + " is not part of store " + scope.storeId());
        }
        return id;
    }

    /** The most recently observed reading. */
    public Optional<InventoryObservation> latest(TrustedStoreScope scope, UUID variantId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM inventory_observation
                        WHERE variant_id = :variantId AND organization_id = :organizationId AND store_id = :storeId
                        ORDER BY observed_at DESC, ingested_at DESC
                        LIMIT 1
                        """)
                .param("variantId", variantId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(InventoryRepository::map)
                .optional();
    }

    /** Readings observed in [from, toExclusive), oldest first. */
    public List<InventoryObservation> observed(TrustedStoreScope scope, UUID variantId, Instant from,
            Instant toExclusive) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM inventory_observation
                        WHERE variant_id = :variantId AND organization_id = :organizationId AND store_id = :storeId
                          AND observed_at >= :from AND observed_at < :toExclusive
                        ORDER BY observed_at, ingested_at
                        """)
                .param("variantId", variantId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("from", Timestamp.from(from))
                .param("toExclusive", Timestamp.from(toExclusive))
                .query(InventoryRepository::map)
                .list();
    }

    private static InventoryObservation map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp sourceUpdatedAt = rs.getTimestamp("source_updated_at");
        InventoryReading reading = new InventoryReading(
                rs.getObject("stock_quantity", Integer.class),
                StockStatus.valueOf(rs.getString("stock_status")),
                ReservationSemantics.valueOf(rs.getString("reservation_semantics")),
                rs.getObject("reserved_quantity", Integer.class),
                ObservationSource.valueOf(rs.getString("observation_source")),
                sourceUpdatedAt == null ? null : sourceUpdatedAt.toInstant(),
                rs.getTimestamp("observed_at").toInstant());
        return new InventoryObservation(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class),
                reading, rs.getTimestamp("ingested_at").toInstant());
    }
}
