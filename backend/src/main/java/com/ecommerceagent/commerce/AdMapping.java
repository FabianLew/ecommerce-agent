package com.ecommerceagent.commerce;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The variants an ad promotes, with a version that changes on every change. A verification holds only for the
 * creative it was made for: {@link #currentFor(Ad)} is false once the ad's creative changed (IG-09).
 */
public record AdMapping(UUID id, UUID adId, AdMappingScope scope, int version, MappingVerification verification,
        Instant verifiedAt, String verifiedCreativeFingerprint, List<UUID> variantIds) {

    public AdMapping {
        variantIds = List.copyOf(variantIds);
    }

    public boolean currentFor(Ad ad) {
        return verification == MappingVerification.VERIFIED && ad.id().equals(adId)
                && ad.creativeFingerprint().equals(verifiedCreativeFingerprint);
    }
}
