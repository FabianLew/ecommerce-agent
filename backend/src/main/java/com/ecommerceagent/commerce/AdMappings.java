package com.ecommerceagent.commerce;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operator's view and confirmation of ad mappings (B-32, ADR-0011 point 7). Only a person with
 * {@code STORE_MANAGE} confirms or revokes a mapping; a confirmation holds for the creative the ad has right now, so a
 * new creative needs a new confirmation (IG-09). Every change names the version the operator saw.
 */
@Service
public class AdMappings {

    private static final String RESOURCE = "ad_mapping";
    static final int MAX_VARIANTS = 200;

    private final AdRepository ads;
    private final CatalogRepository catalog;
    private final StoreAccess storeAccess;
    private final AuditTrail audit;
    private final Clock clock;

    AdMappings(AdRepository ads, CatalogRepository catalog, StoreAccess storeAccess, AuditTrail audit, Clock clock) {
        this.ads = ads;
        this.catalog = catalog;
        this.storeAccess = storeAccess;
        this.audit = audit;
        this.clock = clock;
    }

    /** An ad with its mapping (if any) and the mapped variants, for the mapping screen. */
    public record AdOverview(Ad ad, AdMapping mapping, List<Variant> variants, MappingState state) {
    }

    /** What the screen shows: the mapping's verification seen against the ad's current creative. */
    public enum MappingState {
        NONE,
        UNVERIFIED,
        VERIFIED,
        CREATIVE_CHANGED,
        REVOKED
    }

    public List<AdOverview> list(TrustedStoreScope scope) {
        Map<UUID, Variant> variants = catalog.variants(scope).stream()
                .collect(Collectors.toMap(Variant::id, Function.identity()));
        return ads.ads(scope).stream().map(ad -> overview(scope, ad, variants)).toList();
    }

    public AdOverview get(TrustedStoreScope scope, UUID adId) {
        Ad ad = ads.ad(scope, adId).orElseThrow(AdMappings::notFound);
        Map<UUID, Variant> variants = catalog.variants(scope).stream()
                .collect(Collectors.toMap(Variant::id, Function.identity()));
        return overview(scope, ad, variants);
    }

    /**
     * Records the operator's confirmation of what the ad promotes. {@code verified} confirms it for the ad's current
     * creative; {@code expectedVersion} is the mapping version the operator saw (0 when there was none).
     */
    @Transactional
    public AdOverview confirm(TrustedStoreScope scope, UUID adId, AdMappingScope mappingScope, List<UUID> variantIds,
            boolean verified, int expectedVersion) {
        if (mappingScope == null) {
            throw invalid("scope", "REQUIRED", "Choose whether the ad promotes one variant or several products");
        }
        Set<UUID> unique = new LinkedHashSet<>(variantIds == null ? List.of() : variantIds);
        if (unique.isEmpty()) {
            throw invalid("variantIds", "REQUIRED", "Choose at least one variant");
        }
        if (unique.size() > MAX_VARIANTS) {
            throw invalid("variantIds", "TOO_MANY", "At most " + MAX_VARIANTS + " variants");
        }
        if (mappingScope == AdMappingScope.SINGLE_VARIANT && unique.size() != 1) {
            throw invalid("variantIds", "SINGLE_VARIANT_NEEDS_ONE", "A single-variant ad promotes exactly one variant");
        }
        // Confirming a mapping widens what the agent may propose: re-check the membership first (ADR-0004).
        TrustedStoreScope fresh = storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE);
        Ad ad = ads.lockAd(fresh, adId).orElseThrow(AdMappings::notFound);
        for (UUID variantId : unique) {
            if (catalog.variant(fresh, variantId).isEmpty()) {
                throw invalid("variantIds", "UNKNOWN_VARIANT", "A chosen variant is not in this store");
            }
        }
        Optional<AdMapping> existing = ads.mappingOfAd(fresh, adId);
        requireVersion(existing, expectedVersion);
        Instant now = clock.instant();
        MappingVerification verification = verified ? MappingVerification.VERIFIED : MappingVerification.UNVERIFIED;
        AdMapping saved = ads.saveMapping(fresh, adId, mappingScope, List.copyOf(unique), verification,
                verified ? now : null);
        if (verified && existing.isPresent() && saved.version() == existing.get().version()) {
            saved = ads.refreshVerification(fresh, saved.id(), now);
        }
        audit.record(AuditEntry.inScope(fresh, verified ? "AD_MAPPING_VERIFIED" : "AD_MAPPING_SAVED", RESOURCE,
                saved.id().toString(), Map.of("adId", adId.toString(), "scope", mappingScope.name(), "variants",
                        Integer.toString(unique.size()), "version", Integer.toString(saved.version()),
                        "creative", ad.creativeFingerprint())));
        return get(fresh, adId);
    }

    /** Withdraws a confirmation: the ad is no longer a pause candidate until it is confirmed again. */
    @Transactional
    public AdOverview revoke(TrustedStoreScope scope, UUID adId, int expectedVersion) {
        TrustedStoreScope fresh = storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE);
        ads.lockAd(fresh, adId).orElseThrow(AdMappings::notFound);
        AdMapping existing = ads.mappingOfAd(fresh, adId)
                .orElseThrow(() -> invalid("mapping", "NO_MAPPING", "The ad has no mapping"));
        requireVersion(Optional.of(existing), expectedVersion);
        AdMapping saved = ads.saveMapping(fresh, adId, existing.scope(), existing.variantIds(),
                MappingVerification.REVOKED, null);
        audit.record(AuditEntry.inScope(fresh, "AD_MAPPING_REVOKED", RESOURCE, saved.id().toString(),
                Map.of("adId", adId.toString(), "version", Integer.toString(saved.version()))));
        return get(fresh, adId);
    }

    private AdOverview overview(TrustedStoreScope scope, Ad ad, Map<UUID, Variant> variants) {
        AdMapping mapping = ads.mappingOfAd(scope, ad.id()).orElse(null);
        List<Variant> mapped = mapping == null ? List.of()
                : mapping.variantIds().stream().map(variants::get).filter(v -> v != null).toList();
        return new AdOverview(ad, mapping, mapped, state(ad, mapping));
    }

    static MappingState state(Ad ad, AdMapping mapping) {
        if (mapping == null) {
            return MappingState.NONE;
        }
        return switch (mapping.verification()) {
            case UNVERIFIED -> MappingState.UNVERIFIED;
            case REVOKED -> MappingState.REVOKED;
            case VERIFIED -> mapping.currentFor(ad) ? MappingState.VERIFIED : MappingState.CREATIVE_CHANGED;
        };
    }

    private static void requireVersion(Optional<AdMapping> existing, int expectedVersion) {
        int current = existing.map(AdMapping::version).orElse(0);
        if (current != expectedVersion) {
            throw new AdMappingException(AdMappingException.Reason.VERSION_CONFLICT, "expectedVersion",
                    "MAPPING_CHANGED", "The mapping changed since it was loaded");
        }
    }

    private static AdMappingException invalid(String field, String code, String message) {
        return new AdMappingException(AdMappingException.Reason.INVALID, field, code, message);
    }

    private static AdMappingException notFound() {
        return new AdMappingException(AdMappingException.Reason.NOT_FOUND, null, "NOT_FOUND", "Ad not found");
    }
}
