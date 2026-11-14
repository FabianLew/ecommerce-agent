package com.ecommerceagent.commerce;

import java.util.List;
import java.util.UUID;

/**
 * Ads and mappings of one store, standing in for the Meta adapter until F2. Variants are named by SKU;
 * {@code verified} mappings are confirmed for the ad's current creative.
 */
public record AdFixture(UUID organizationId, UUID storeId, List<FixtureAd> ads) {

    public AdFixture {
        ads = List.copyOf(ads);
    }

    public record FixtureAd(String externalId, String accountExternalId, String name, AdStatus ownStatus,
            String effectiveStatus, String creativeFingerprint, AdMappingScope scope, List<String> skus,
            boolean verified) {

        public FixtureAd {
            skus = List.copyOf(skus);
        }
    }
}
