package com.ecommerceagent.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.commerce.Ad;
import com.ecommerceagent.commerce.AdMapping;
import com.ecommerceagent.commerce.AdMappingException;
import com.ecommerceagent.commerce.AdMappingScope;
import com.ecommerceagent.commerce.AdMappings;
import com.ecommerceagent.commerce.AdMappings.AdOverview;
import com.ecommerceagent.commerce.AdMappings.MappingState;
import com.ecommerceagent.commerce.AdSource;
import com.ecommerceagent.commerce.AdStatus;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.MappingVerification;
import com.ecommerceagent.commerce.Variant;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ads and the operator's confirmation of what each ad promotes (B-32). */
@RestController
@RequestMapping("/api/v1/stores/{storeId}")
class AdMappingController {

    private final AdMappings mappings;
    private final CatalogRepository catalog;

    AdMappingController(AdMappings mappings, CatalogRepository catalog) {
        this.mappings = mappings;
        this.catalog = catalog;
    }

    @GetMapping("/ads")
    ListResponse<AdResponse> ads(TrustedStoreScope scope) {
        return ListResponse.complete(mappings.list(scope).stream().map(o -> AdResponse.from(o, scope)).toList());
    }

    @GetMapping("/ads/{adId}")
    AdResponse ad(TrustedStoreScope scope, @PathVariable String adId) {
        return AdResponse.from(mappings.get(scope, id(adId)), scope);
    }

    @PutMapping("/ads/{adId}/mapping")
    AdResponse confirm(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @PathVariable String adId, @RequestBody MappingRequest request) {
        return AdResponse.from(mappings.confirm(scope, id(adId), request.scope(), request.variantIds(),
                request.verified(), request.expectedVersion()), scope);
    }

    @PostMapping("/ads/{adId}/mapping/revocation")
    AdResponse revoke(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @PathVariable String adId, @RequestBody RevokeRequest request) {
        return AdResponse.from(mappings.revoke(scope, id(adId), request.expectedVersion()), scope);
    }

    /** Variants to choose from when mapping an ad. */
    @GetMapping("/variants")
    ListResponse<VariantResponse> variants(TrustedStoreScope scope) {
        return ListResponse.complete(catalog.variants(scope).stream().map(VariantResponse::from).toList());
    }

    private static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new AdMappingException(AdMappingException.Reason.NOT_FOUND, null, "NOT_FOUND", "Ad not found");
        }
    }

    enum AdAction {
        CONFIRM_MAPPING,
        REVOKE_MAPPING
    }

    record MappingRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AdMappingScope scope,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> variantIds,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Confirm for the ad's current creative")
            boolean verified,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The version seen; 0 without a mapping")
            int expectedVersion) {
    }

    record RevokeRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int expectedVersion) {
    }

    record VariantResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            String sku,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String productName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {

        static VariantResponse from(Variant variant) {
            return new VariantResponse(variant.id(), variant.sku(), variant.productName(), variant.name());
        }
    }

    record MappingResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AdMappingScope scope,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int version,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MappingVerification verification,
            Instant verifiedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<VariantResponse> variants) {
    }

    record AdResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String externalId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accountExternalId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AdStatus ownStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String effectiveStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant observedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AdSource source,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MappingState mappingState,
            MappingResponse mapping,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AdAction> allowedActions) {

        static AdResponse from(AdOverview overview, TrustedStoreScope scope) {
            Ad ad = overview.ad();
            AdMapping mapping = overview.mapping();
            List<AdAction> actions = !scope.has(StorePermission.STORE_MANAGE) ? List.of()
                    : mapping != null && mapping.verification() == MappingVerification.VERIFIED
                            ? List.of(AdAction.CONFIRM_MAPPING, AdAction.REVOKE_MAPPING)
                            : List.of(AdAction.CONFIRM_MAPPING);
            MappingResponse mappingResponse = mapping == null ? null
                    : new MappingResponse(mapping.id(), mapping.scope(), mapping.version(), mapping.verification(),
                            mapping.verifiedAt(), overview.variants().stream().map(VariantResponse::from).toList());
            return new AdResponse(ad.id(), ad.externalId(), ad.accountExternalId(), ad.name(), ad.ownStatus(),
                    ad.effectiveStatus(), ad.observedAt(), ad.source(), overview.state(), mappingResponse, actions);
        }
    }
}
