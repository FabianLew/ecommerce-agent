package com.ecommerceagent.intelligence.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.decisions.EvidenceRef;

/** Contexts for proposer and policy tests: the 03 §2 scenario (18 units, 49 sold in 7 days), adjustable. */
public final class InventoryContextFixture {

    public static final Instant AS_OF = Instant.parse("2026-11-14T08:06:00Z");
    public static final UUID VARIANT = UUID.fromString("c0000000-0000-4000-8000-000000000826");
    public static final UUID AD_9001 = UUID.fromString("c0000000-0000-4000-8000-000000009001");
    public static final UUID AD_9002 = UUID.fromString("c0000000-0000-4000-8000-000000009002");

    public Instant stockConfirmedAt = AS_OF.minusSeconds(10);
    public BigDecimal daysOfCover = new BigDecimal("2.5714");
    public String demandQuality = "CONFIRMED";
    public String lastRiskOutcome = "AT_RISK";
    public final List<InventoryContext.AdFact> ads = new ArrayList<>(List.of(dedicated(), catalog()));

    public static InventoryContext.AdFact dedicated() {
        return ad(AD_9001, "ad-9001", "SINGLE_VARIANT", 1, "VERIFIED", true, "ACTIVE");
    }

    public static InventoryContext.AdFact catalog() {
        return ad(AD_9002, "ad-9002", "MULTI_PRODUCT", 2, "UNVERIFIED", false, "ACTIVE");
    }

    public static InventoryContext.AdFact ad(UUID id, String externalId, String scope, int variants,
            String verification, boolean current, String status) {
        return new InventoryContext.AdFact(id, externalId, externalId, status, status, AS_OF.minusSeconds(20), scope,
                1, verification, "VERIFIED".equals(verification) ? AS_OF.minusSeconds(3600) : null, current, variants,
                ref(), ref());
    }

    public InventoryContext build() {
        return new InventoryContext(UUID.randomUUID(), UUID.randomUUID(), VARIANT, AS_OF,
                new InventoryContext.Stock(ref(), 18, "IN_STOCK", AS_OF.minusSeconds(300), stockConfirmedAt),
                new InventoryContext.Metric(ref(), daysOfCover == null ? null : new BigDecimal("49"),
                        daysOfCover == null ? "UNAVAILABLE" : demandQuality,
                        daysOfCover == null ? "SALES_HISTORY_INCOMPLETE" : null),
                new InventoryContext.Metric(ref(), daysOfCover == null ? null : new BigDecimal("7.0000"),
                        daysOfCover == null ? "UNAVAILABLE" : demandQuality,
                        daysOfCover == null ? "SALES_HISTORY_INCOMPLETE" : null),
                new InventoryContext.Metric(ref(), daysOfCover, daysOfCover == null ? "UNAVAILABLE" : demandQuality,
                        daysOfCover == null ? "SALES_HISTORY_INCOMPLETE" : null),
                ref(), false, new BigDecimal("3"), lastRiskOutcome, ads);
    }

    /** Adjusts the dedicated ad of a fixture. */
    public static final class AdFactBuilder {

        private AdFactBuilder() {
        }

        public static void verifiedAt(InventoryContextFixture fixture, Instant verifiedAt) {
            InventoryContext.AdFact ad = fixture.ads.getFirst();
            fixture.ads.set(0, new InventoryContext.AdFact(ad.adId(), ad.externalId(), ad.name(), ad.ownStatus(),
                    ad.effectiveStatus(), ad.observedAt(), ad.mappingScope(), ad.mappingVersion(), ad.verification(),
                    verifiedAt, ad.currentForCreative(), ad.variantCount(), ad.stateEvidence(),
                    ad.mappingEvidence()));
        }
    }

    private static EvidenceRef ref() {
        return new EvidenceRef(UUID.randomUUID(), 1);
    }
}
