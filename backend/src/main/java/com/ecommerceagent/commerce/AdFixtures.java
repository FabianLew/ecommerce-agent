package com.ecommerceagent.commerce;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads ads and mappings from a fixture (B-20). Idempotent: loading the same fixture again changes nothing. */
@Service
public class AdFixtures {

    private final AdRepository ads;
    private final CatalogRepository catalog;
    private final Clock clock;

    AdFixtures(AdRepository ads, CatalogRepository catalog, Clock clock) {
        this.ads = ads;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** Returns the ids of the loaded ads; an ad whose SKUs are not in the catalog yet is skipped. */
    @Transactional
    public List<UUID> load(TrustedStoreScope scope, AdFixture fixture) {
        if (!scope.storeId().equals(fixture.storeId())) {
            throw new IllegalArgumentException("Fixture belongs to another store");
        }
        Map<String, UUID> bySku = catalog.variants(scope).stream()
                .filter(variant -> variant.sku() != null)
                .collect(Collectors.toMap(Variant::sku, Variant::id, (a, b) -> a));
        Instant now = clock.instant();
        List<UUID> loaded = new ArrayList<>();
        for (AdFixture.FixtureAd item : fixture.ads()) {
            List<UUID> variants = item.skus().stream().map(bySku::get).toList();
            if (variants.contains(null)) {
                continue;
            }
            UUID adId = ads.upsertAd(scope, new AdData(item.externalId(), item.accountExternalId(), item.name(),
                    item.ownStatus(), item.effectiveStatus(), item.creativeFingerprint(), now, AdSource.FIXTURE));
            ads.saveMapping(scope, adId, item.scope(), variants,
                    item.verified() ? MappingVerification.VERIFIED : MappingVerification.UNVERIFIED,
                    item.verified() ? now : null);
            loaded.add(adId);
        }
        return loaded;
    }
}
