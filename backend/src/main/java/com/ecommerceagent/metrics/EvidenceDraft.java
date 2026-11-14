package com.ecommerceagent.metrics;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Evidence before it is stored. {@code resourceRef} names the resource, e.g. {@code PRODUCT_VARIANT:{id}};
 * {@code content} must be canonical-JSON safe (no decimal numbers).
 */
public record EvidenceDraft(EvidenceType type, String resourceRef, String definitionVersion, JsonNode content,
        List<UUID> inputEvidenceIds, Instant observedAt, Instant availableAt, Instant computedAt) {

    public EvidenceDraft {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(resourceRef, "resourceRef");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(availableAt, "availableAt");
        Objects.requireNonNull(computedAt, "computedAt");
        inputEvidenceIds = inputEvidenceIds == null ? List.of() : List.copyOf(inputEvidenceIds);
    }

    public static String variantRef(UUID variantId) {
        return "PRODUCT_VARIANT:" + variantId;
    }

    public static String adRef(UUID adId) {
        return "AD:" + adId;
    }

    public static String adMappingRef(UUID mappingId) {
        return "AD_MAPPING:" + mappingId;
    }
}
