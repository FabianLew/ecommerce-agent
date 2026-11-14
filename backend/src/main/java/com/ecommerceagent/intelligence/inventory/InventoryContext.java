package com.ecommerceagent.intelligence.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.decisions.EvidenceRef;
import tools.jackson.databind.JsonNode;

/**
 * Typed view of an {@code inventory-context/1} snapshot, the only input of proposers and the policy. Nullable
 * fields mean the fact was not available when the context was built.
 */
public record InventoryContext(UUID snapshotId, UUID opportunityId, UUID variantId, Instant asOf, Stock stock,
        Metric unitsSold, Metric velocityPerDay, Metric daysOfCover, EvidenceRef replenishmentEvidence,
        boolean replenishmentKnown, BigDecimal thresholdDays, String lastRiskOutcome, List<AdFact> ads) {

    public static final String CONTENT_SCHEMA = "inventory-context/1";

    public InventoryContext {
        ads = List.copyOf(ads);
    }

    public record Stock(EvidenceRef evidence, Integer availableToSell, String stockStatus, Instant observedAt,
            Instant confirmedAt) {
    }

    public record Metric(EvidenceRef evidence, BigDecimal value, String quality, String unavailableReason) {
    }

    public record AdFact(UUID adId, String externalId, String name, String ownStatus, String effectiveStatus,
            Instant observedAt, String mappingScope, int mappingVersion, String verification, Instant verifiedAt,
            boolean currentForCreative, int variantCount, EvidenceRef stateEvidence, EvidenceRef mappingEvidence) {

        public boolean singleVariant() {
            return "SINGLE_VARIANT".equals(mappingScope) && variantCount == 1;
        }

        public boolean active() {
            return "ACTIVE".equals(ownStatus) && "ACTIVE".equals(effectiveStatus);
        }
    }

    public static InventoryContext of(ContextSnapshot snapshot) {
        if (!CONTENT_SCHEMA.equals(snapshot.contentSchema())) {
            throw new IllegalArgumentException("Not an inventory context: " + snapshot.contentSchema());
        }
        JsonNode c = snapshot.content();
        JsonNode inventory = c.get("inventory");
        Stock stock = new Stock(ref(inventory.get("evidence")), integer(inventory.get("availableToSell")),
                text(inventory.get("stockStatus")), instant(inventory.get("observedAt")),
                instant(inventory.get("confirmedAt")));
        JsonNode demand = c.get("demand");
        List<AdFact> ads = new ArrayList<>();
        for (JsonNode ad : c.get("ads")) {
            ads.add(new AdFact(UUID.fromString(ad.get("adId").asString()), text(ad.get("externalId")),
                    text(ad.get("name")), text(ad.get("ownStatus")), text(ad.get("effectiveStatus")),
                    instant(ad.get("observedAt")), text(ad.get("mappingScope")), ad.get("mappingVersion").asInt(),
                    text(ad.get("verification")), instant(ad.get("verifiedAt")),
                    ad.get("currentForCreative").asBoolean(), ad.get("variantCount").asInt(),
                    ref(ad.get("stateEvidence")), ref(ad.get("mappingEvidence"))));
        }
        JsonNode risk = c.get("risk");
        return new InventoryContext(snapshot.id(), UUID.fromString(c.get("opportunityId").asString()),
                UUID.fromString(c.get("variantId").asString()), Instant.parse(c.get("asOf").asString()), stock,
                metric(demand.get("unitsSold")), metric(demand.get("velocityPerDay")),
                metric(demand.get("daysOfCover")), ref(c.get("replenishment").get("evidence")),
                c.get("replenishment").get("known").asBoolean(), new BigDecimal(risk.get("thresholdDays").asString()),
                text(risk.get("lastOutcome")), ads);
    }

    private static Metric metric(JsonNode node) {
        String value = text(node.get("value"));
        return new Metric(ref(node.get("evidence")), value == null ? null : new BigDecimal(value),
                text(node.get("quality")), text(node.get("unavailableReason")));
    }

    private static EvidenceRef ref(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new EvidenceRef(UUID.fromString(node.get("evidenceId").asString()), node.get("version").asInt());
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static Integer integer(JsonNode node) {
        return node == null || node.isNull() ? null : node.asInt();
    }

    private static Instant instant(JsonNode node) {
        String value = text(node);
        return value == null ? null : Instant.parse(value);
    }
}
