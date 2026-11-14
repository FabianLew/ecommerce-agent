package com.ecommerceagent.commerce;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Ads and their SKU mappings (B-20, ADR-0011). Every mapping change increments its version. */
@Repository
public class AdRepository {

    static final String META = "META";

    private final JdbcClient jdbc;
    private final Clock clock;

    AdRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public UUID upsertAd(TrustedStoreScope scope, AdData ad) {
        Instant now = clock.instant();
        return jdbc.sql("""
                        INSERT INTO ad (id, organization_id, store_id, platform, external_id, account_external_id,
                                        name, own_status, effective_status, creative_fingerprint, observed_at, source,
                                        created_at, updated_at)
                        VALUES (:id, :organizationId, :storeId, :platform, :externalId, :account, :name, :ownStatus,
                                :effectiveStatus, :fingerprint, :observedAt, :source, :now, :now)
                        ON CONFLICT (store_id, platform, external_id) DO UPDATE SET
                            account_external_id = EXCLUDED.account_external_id, name = EXCLUDED.name,
                            own_status = EXCLUDED.own_status, effective_status = EXCLUDED.effective_status,
                            creative_fingerprint = EXCLUDED.creative_fingerprint,
                            observed_at = EXCLUDED.observed_at, source = EXCLUDED.source,
                            updated_at = EXCLUDED.updated_at
                        WHERE ad.organization_id = EXCLUDED.organization_id
                        RETURNING id
                        """)
                .param("id", UUID.randomUUID())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("platform", META)
                .param("externalId", ad.externalId())
                .param("account", ad.accountExternalId())
                .param("name", ad.name())
                .param("ownStatus", ad.ownStatus().name())
                .param("effectiveStatus", ad.effectiveStatus())
                .param("fingerprint", ad.creativeFingerprint())
                .param("observedAt", Timestamp.from(ad.observedAt()))
                .param("source", ad.source().name())
                .param("now", Timestamp.from(now))
                .query(UUID.class)
                .single();
    }

    public Optional<Ad> ad(TrustedStoreScope scope, UUID adId) {
        return jdbc.sql(AD_SELECT + " WHERE a.id = :id AND a.organization_id = :organizationId AND a.store_id = :storeId")
                .param("id", adId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(AdRepository::ad)
                .optional();
    }

    /**
     * Sets the mapping of an ad. Variants must belong to the store. The version increments only when the
     * scope, variants or verification change; a verified mapping records the creative it was confirmed for.
     */
    @Transactional
    public AdMapping saveMapping(TrustedStoreScope scope, UUID adId, AdMappingScope mappingScope,
            List<UUID> variantIds, MappingVerification verification, Instant verifiedAt) {
        Ad ad = ad(scope, adId).orElseThrow(() -> new IllegalArgumentException("Unknown ad " + adId));
        if (variantIds.isEmpty()) {
            throw new IllegalArgumentException("A mapping needs at least one variant");
        }
        if (mappingScope == AdMappingScope.SINGLE_VARIANT && Set.copyOf(variantIds).size() != 1) {
            throw new IllegalArgumentException("A single-variant mapping names exactly one variant");
        }
        int known = jdbc.sql("""
                        SELECT count(*) FROM product_variant
                        WHERE id IN (:ids) AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("ids", List.copyOf(Set.copyOf(variantIds)))
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(Integer.class)
                .single();
        if (known != Set.copyOf(variantIds).size()) {
            throw new IllegalArgumentException("Mapping refers to variants outside the store");
        }
        boolean verified = verification == MappingVerification.VERIFIED;
        Instant verifiedTime = verified ? Objects.requireNonNull(verifiedAt, "verifiedAt") : null;
        String fingerprint = verified ? ad.creativeFingerprint() : null;

        Optional<AdMapping> existing = mappingOfAd(scope, adId);
        if (existing.isPresent() && sameMapping(existing.get(), mappingScope, variantIds, verification,
                fingerprint)) {
            return existing.get();
        }
        Instant now = clock.instant();
        UUID mappingId = jdbc.sql("""
                        INSERT INTO ad_mapping (id, organization_id, store_id, ad_id, scope, version,
                                                verification_status, verified_at, verified_creative_fingerprint,
                                                created_at, updated_at)
                        VALUES (:id, :organizationId, :storeId, :adId, :scope, 1, :verification, :verifiedAt,
                                :fingerprint, :now, :now)
                        ON CONFLICT (store_id, ad_id) DO UPDATE SET
                            scope = EXCLUDED.scope, version = ad_mapping.version + 1,
                            verification_status = EXCLUDED.verification_status, verified_at = EXCLUDED.verified_at,
                            verified_creative_fingerprint = EXCLUDED.verified_creative_fingerprint,
                            updated_at = EXCLUDED.updated_at
                        WHERE ad_mapping.organization_id = EXCLUDED.organization_id
                        RETURNING id
                        """)
                .param("id", UUID.randomUUID())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("adId", adId)
                .param("scope", mappingScope.name())
                .param("verification", verification.name())
                .param("verifiedAt", verifiedTime == null ? null : Timestamp.from(verifiedTime))
                .param("fingerprint", fingerprint)
                .param("now", Timestamp.from(now))
                .query(UUID.class)
                .single();
        jdbc.sql("DELETE FROM ad_mapping_variant WHERE mapping_id = :id AND store_id = :storeId")
                .param("id", mappingId)
                .param("storeId", scope.storeId())
                .update();
        for (UUID variantId : Set.copyOf(variantIds)) {
            jdbc.sql("""
                            INSERT INTO ad_mapping_variant (mapping_id, variant_id, organization_id, store_id)
                            VALUES (:mappingId, :variantId, :organizationId, :storeId)
                            """)
                    .param("mappingId", mappingId)
                    .param("variantId", variantId)
                    .param("organizationId", scope.organizationId())
                    .param("storeId", scope.storeId())
                    .update();
        }
        return mappingOfAd(scope, adId).orElseThrow();
    }

    public Optional<AdMapping> mappingOfAd(TrustedStoreScope scope, UUID adId) {
        return jdbc.sql(MAPPING_SELECT + " AND m.ad_id = :adId")
                .param("adId", adId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(AdRepository::mapping)
                .optional();
    }

    /** Ads mapped to a variant, single-variant mappings first, then by external id. */
    public List<MappedAd> adsForVariant(TrustedStoreScope scope, UUID variantId) {
        List<AdMapping> mappings = jdbc.sql(MAPPING_SELECT + """
                         AND EXISTS (SELECT 1 FROM ad_mapping_variant v
                                     WHERE v.mapping_id = m.id AND v.variant_id = :variantId
                                       AND v.store_id = m.store_id)
                        """)
                .param("variantId", variantId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(AdRepository::mapping)
                .list();
        return mappings.stream()
                .map(mapping -> new MappedAd(ad(scope, mapping.adId()).orElseThrow(), mapping))
                .sorted((a, b) -> {
                    int byScope = a.mapping().scope().compareTo(b.mapping().scope());
                    return byScope != 0 ? byScope : a.ad().externalId().compareTo(b.ad().externalId());
                })
                .toList();
    }

    private static boolean sameMapping(AdMapping existing, AdMappingScope scope, List<UUID> variantIds,
            MappingVerification verification, String fingerprint) {
        return existing.scope() == scope && existing.verification() == verification
                && new HashSet<>(existing.variantIds()).equals(Set.copyOf(variantIds))
                && Objects.equals(existing.verifiedCreativeFingerprint(), fingerprint);
    }

    private static final String AD_SELECT = """
            SELECT a.id, a.platform, a.external_id, a.account_external_id, a.name, a.own_status, a.effective_status,
                   a.creative_fingerprint, a.observed_at, a.source
            FROM ad a""";

    private static final String MAPPING_SELECT = """
            SELECT m.id, m.ad_id, m.scope, m.version, m.verification_status, m.verified_at,
                   m.verified_creative_fingerprint,
                   ARRAY(SELECT v.variant_id FROM ad_mapping_variant v
                         WHERE v.mapping_id = m.id AND v.store_id = m.store_id ORDER BY v.variant_id) AS variant_ids
            FROM ad_mapping m
            WHERE m.organization_id = :organizationId AND m.store_id = :storeId""";

    private static Ad ad(ResultSet rs, int row) throws SQLException {
        return new Ad(rs.getObject("id", UUID.class), rs.getString("platform"), rs.getString("external_id"),
                rs.getString("account_external_id"), rs.getString("name"), AdStatus.valueOf(rs.getString("own_status")),
                rs.getString("effective_status"), rs.getString("creative_fingerprint"),
                rs.getTimestamp("observed_at").toInstant(), AdSource.valueOf(rs.getString("source")));
    }

    private static AdMapping mapping(ResultSet rs, int row) throws SQLException {
        Object[] variants = (Object[]) rs.getArray("variant_ids").getArray();
        Timestamp verifiedAt = rs.getTimestamp("verified_at");
        return new AdMapping(rs.getObject("id", UUID.class), rs.getObject("ad_id", UUID.class),
                AdMappingScope.valueOf(rs.getString("scope")), rs.getInt("version"),
                MappingVerification.valueOf(rs.getString("verification_status")),
                verifiedAt == null ? null : verifiedAt.toInstant(), rs.getString("verified_creative_fingerprint"),
                java.util.Arrays.stream(variants).map(id -> (UUID) id).toList());
    }
}
