package com.ecommerceagent.intelligence.inventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.metrics.CanonicalJson;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** An {@code inventory-context/1} snapshot of the 03 §2 scenario for tests without a database. */
public final class SnapshotFixture {

    public static final UUID OPPORTUNITY = UUID.fromString("d0000000-0000-4000-8000-000000001001");
    public static final UUID SNAPSHOT = UUID.fromString("d0000000-0000-4000-8000-000000002001");
    public static final UUID STOCK_EVIDENCE = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    public static final UUID COVER_EVIDENCE = UUID.fromString("e0000000-0000-4000-8000-000000000002");
    public static final UUID REPLENISHMENT_EVIDENCE = UUID.fromString("e0000000-0000-4000-8000-000000000003");
    public static final UUID AD_STATE_EVIDENCE = UUID.fromString("e0000000-0000-4000-8000-000000000004");
    public static final UUID AD_MAPPING_EVIDENCE = UUID.fromString("e0000000-0000-4000-8000-000000000005");
    public static final UUID CATALOG_MAPPING_EVIDENCE = UUID.fromString("e0000000-0000-4000-8000-000000000006");
    public static final UUID CATALOG_STATE_EVIDENCE = UUID.fromString("e0000000-0000-4000-8000-000000000007");

    private SnapshotFixture() {
    }

    public static ContextSnapshot serum() {
        Instant asOf = InventoryContextFixture.AS_OF;
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ObjectNode content = nodes.objectNode();
        content.put("opportunityId", OPPORTUNITY.toString());
        content.put("variantId", InventoryContextFixture.VARIANT.toString());
        content.put("asOf", asOf.toString());
        ObjectNode inventory = content.putObject("inventory");
        inventory.set("evidence", ref(STOCK_EVIDENCE));
        inventory.put("availableToSell", 18);
        inventory.put("stockStatus", "IN_STOCK");
        inventory.put("observedAt", asOf.minusSeconds(30).toString());
        inventory.put("confirmedAt", asOf.minusSeconds(10).toString());
        ObjectNode demand = content.putObject("demand");
        metric(demand.putObject("unitsSold"), "49", COVER_EVIDENCE);
        metric(demand.putObject("velocityPerDay"), "7.0000", COVER_EVIDENCE);
        metric(demand.putObject("daysOfCover"), "2.5714", COVER_EVIDENCE);
        ObjectNode replenishment = content.putObject("replenishment");
        replenishment.set("evidence", ref(REPLENISHMENT_EVIDENCE));
        replenishment.put("known", false);
        ObjectNode risk = content.putObject("risk");
        risk.put("thresholdDays", "3");
        risk.put("detectorVersion", "inventory-risk/1");
        risk.put("lastOutcome", "AT_RISK");
        ArrayNode ads = content.putArray("ads");
        ad(ads.addObject(), InventoryContextFixture.AD_9001, "ad-9001", "Serum 30 ml – reklama dedykowana",
                "SINGLE_VARIANT", 1, "VERIFIED", true, AD_STATE_EVIDENCE, AD_MAPPING_EVIDENCE, asOf);
        ad(ads.addObject(), InventoryContextFixture.AD_9002, "ad-9002", "Katalog – wszystkie produkty",
                "MULTI_PRODUCT", 2, "UNVERIFIED", false, CATALOG_STATE_EVIDENCE, CATALOG_MAPPING_EVIDENCE, asOf);
        List<UUID> evidence = new ArrayList<>(List.of(STOCK_EVIDENCE, COVER_EVIDENCE, REPLENISHMENT_EVIDENCE,
                AD_STATE_EVIDENCE, AD_MAPPING_EVIDENCE, CATALOG_MAPPING_EVIDENCE, CATALOG_STATE_EVIDENCE));
        return new ContextSnapshot(SNAPSHOT, OPPORTUNITY, InventoryContext.CONTENT_SCHEMA, asOf, content,
                CanonicalJson.hash(content), evidence, asOf);
    }

    private static void metric(ObjectNode node, String value, UUID evidence) {
        node.set("evidence", ref(evidence));
        node.put("value", value);
        node.put("quality", "CONFIRMED");
        node.putNull("unavailableReason");
    }

    private static void ad(ObjectNode ad, UUID id, String externalId, String name, String scope, int variants,
            String verification, boolean current, UUID state, UUID mapping, Instant asOf) {
        ad.put("adId", id.toString());
        ad.put("externalId", externalId);
        ad.put("name", name);
        ad.put("ownStatus", "ACTIVE");
        ad.put("effectiveStatus", "ACTIVE");
        ad.put("observedAt", asOf.minusSeconds(20).toString());
        ad.put("mappingScope", scope);
        ad.put("mappingVersion", 1);
        ad.put("verification", verification);
        ad.put("verifiedAt", "VERIFIED".equals(verification) ? asOf.minusSeconds(3600).toString() : null);
        ad.put("currentForCreative", current);
        ad.put("variantCount", variants);
        ad.set("stateEvidence", ref(state));
        ad.set("mappingEvidence", ref(mapping));
    }

    private static ObjectNode ref(UUID id) {
        return JsonNodeFactory.instance.objectNode().put("evidenceId", id.toString()).put("version", 1);
    }
}
