package com.ecommerceagent.intelligence.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.decisions.DecisionProposals;
import com.ecommerceagent.decisions.RiskDetectorSettings;
import com.ecommerceagent.decisions.StoredProposal;
import com.ecommerceagent.intelligence.agent.AgentTool;
import com.ecommerceagent.intelligence.agent.ToolDefinition;
import com.ecommerceagent.intelligence.agent.ToolEffect;
import com.ecommerceagent.intelligence.agent.ToolExecutionContext;
import com.ecommerceagent.intelligence.agent.ToolOutcome;
import com.ecommerceagent.metrics.CanonicalJson;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The five read tools of the Inventory Guardian (document 02 §5, 03 §5; ADR-0016 point 4). They read the frozen
 * snapshot of the analysis; a variant or ad outside it is NOT_FOUND, whatever exists elsewhere (IG-07).
 */
@Configuration(proxyBeanMethods = false)
public class InventoryReadTools {

    public static final String FORECAST = "get_inventory_forecast";
    public static final String REPLENISHMENT = "get_replenishment_plan";
    public static final String MAPPINGS = "get_product_ad_mappings";
    public static final String DELIVERY = "get_ad_delivery_state";
    public static final String RECENT_ACTIONS = "get_recent_actions";
    static final String VERSION = "1.0.0";
    static final int MAX_RESULT_BYTES = 16_384;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Bean
    AgentTool inventoryForecastTool(RiskDetectorSettings settings) {
        Duration maxStockAge = settings.maxStockAge();
        return tool(FORECAST, "Sellable stock, units sold in the last 7 full days, sales velocity per day and days of "
                + "cover of the episode's variant, with quality, freshness and evidence references.",
                schema("productVariantId", "The episode's product variant id."), (context, args) -> {
                    JsonNode content = context.snapshot().content();
                    if (!variant(content, args)) {
                        return notFound("variant");
                    }
                    ObjectNode result = envelope(context.snapshot());
                    JsonNode inventory = content.get("inventory");
                    result.set("stock", inventory.deepCopy());
                    Instant confirmed = latest(instant(inventory.get("observedAt")),
                            instant(inventory.get("confirmedAt")));
                    Instant asOf = context.snapshot().asOf();
                    result.put("stockCurrent", !inventory.path("evidence").isNull() && confirmed != null
                            && !confirmed.isBefore(asOf.minus(maxStockAge)));
                    result.put("stockMaxAgeSeconds", maxStockAge.toSeconds());
                    result.set("demand", content.get("demand").deepCopy());
                    result.put("method", "7 full days in store time; cover = available stock / units per day");
                    result.put("thresholdDays", content.get("risk").get("thresholdDays").asString());
                    return ToolOutcome.ok(result);
                });
    }

    @Bean
    AgentTool replenishmentPlanTool() {
        return tool(REPLENISHMENT, "Confirmed deliveries of the episode's variant. known=false means no delivery is "
                + "confirmed; do not assume one.",
                schema("productVariantId", "The episode's product variant id."), (context, args) -> {
                    JsonNode content = context.snapshot().content();
                    if (!variant(content, args)) {
                        return notFound("variant");
                    }
                    ObjectNode result = envelope(context.snapshot());
                    result.setAll((ObjectNode) content.get("replenishment").deepCopy());
                    result.putArray("deliveries");
                    return ToolOutcome.ok(result);
                });
    }

    @Bean
    AgentTool productAdMappingsTool() {
        return tool(MAPPINGS, "Ads mapped to the episode's variant: mapping scope (SINGLE_VARIANT or MULTI_PRODUCT), "
                + "number of variants covered, verification, whether the mapping is current for the ad's creative, "
                + "and the mapping evidence.",
                schema("productVariantId", "The episode's product variant id."), (context, args) -> {
                    JsonNode content = context.snapshot().content();
                    if (!variant(content, args)) {
                        return notFound("variant");
                    }
                    ObjectNode result = envelope(context.snapshot());
                    ArrayNode ads = result.putArray("ads");
                    for (JsonNode ad : content.get("ads")) {
                        ObjectNode item = ads.addObject();
                        for (String field : new String[] {"adId", "externalId", "name", "mappingScope",
                                "mappingVersion", "variantCount", "verification", "verifiedAt",
                                "currentForCreative", "mappingEvidence"}) {
                            item.set(field, ad.get(field).deepCopy());
                        }
                    }
                    return ToolOutcome.ok(result);
                });
    }

    @Bean
    AgentTool adDeliveryStateTool() {
        return tool(DELIVERY, "Own and effective delivery status of one ad mapped to the episode's variant, when it "
                + "was read, and the state evidence.",
                schema("adId", "The adId returned by get_product_ad_mappings."), (context, args) -> {
                    JsonNode ad = ad(context.snapshot().content(), args.get("adId").asString());
                    if (ad == null) {
                        return notFound("ad");
                    }
                    ObjectNode result = envelope(context.snapshot());
                    for (String field : new String[] {"adId", "externalId", "name", "ownStatus", "effectiveStatus",
                            "observedAt", "stateEvidence"}) {
                        result.set(field, ad.get(field).deepCopy());
                    }
                    return ToolOutcome.ok(result);
                });
    }

    @Bean
    AgentTool recentActionsTool(DecisionProposals proposals) {
        return tool(RECENT_ACTIONS, "Earlier proposals and actions of this episode for the variant or one of its "
                + "mapped ads, up to the snapshot time, and the last risk outcome.",
                schema("resourceId", "The episode's product variant id or an adId of a mapped ad."),
                (context, args) -> {
                    JsonNode content = context.snapshot().content();
                    String resource = args.get("resourceId").asString();
                    if (!resource.equals(content.get("variantId").asString()) && ad(content, resource) == null) {
                        return notFound("resource");
                    }
                    ObjectNode result = envelope(context.snapshot());
                    result.put("lastRiskOutcome", content.get("risk").path("lastOutcome").asString(null));
                    ArrayNode earlier = result.putArray("proposals");
                    UUID opportunityId = context.snapshot().opportunityId();
                    for (StoredProposal stored : proposals.ofOpportunity(context.scope(), opportunityId)) {
                        if (stored.proposal().createdAt().isAfter(context.snapshot().asOf())) {
                            continue;
                        }
                        ObjectNode item = earlier.addObject();
                        item.put("revision", stored.proposal().revision());
                        item.put("disposition", stored.proposal().recommendation().disposition().name());
                        item.put("status", stored.status().name());
                        item.put("createdAt", stored.proposal().createdAt().toString());
                        item.set("action", stored.proposal().recommendation().action() == null ? NODES.nullNode()
                                : com.ecommerceagent.decisions.ProposalJson.write(stored.proposal().recommendation())
                                        .get("action"));
                    }
                    result.putArray("executedActions");
                    result.putArray("activeLocks");
                    return ToolOutcome.ok(result);
                });
    }

    /** The tool body: arguments already match the schema. */
    @FunctionalInterface
    interface Body {
        ToolOutcome apply(ToolExecutionContext context, JsonNode arguments);
    }

    static AgentTool tool(String name, String description, JsonNode schema, Body body) {
        ToolDefinition definition = new ToolDefinition(name, VERSION, description, schema, ToolEffect.READ,
                MAX_RESULT_BYTES);
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolOutcome execute(ToolExecutionContext context, JsonNode arguments) {
                if (!InventoryContext.CONTENT_SCHEMA.equals(context.snapshot().contentSchema())) {
                    return ToolOutcome.failed(ToolOutcome.ToolError.FORBIDDEN, "Not an inventory analysis.");
                }
                return body.apply(context, arguments);
            }
        };
    }

    static JsonNode schema(String field, String description) {
        return CanonicalJson.read("""
                {"type":"object","additionalProperties":false,"required":["%s"],
                 "properties":{"%s":{"type":"string","format":"uuid","minLength":36,"maxLength":36,
                 "description":"%s"}}}
                """.formatted(field, field, description));
    }

    private static boolean variant(JsonNode content, JsonNode args) {
        return content.get("variantId").asString().equals(args.get("productVariantId").asString());
    }

    private static JsonNode ad(JsonNode content, String adId) {
        for (JsonNode ad : content.get("ads")) {
            if (ad.get("adId").asString().equals(adId)) {
                return ad;
            }
        }
        return null;
    }

    private static ObjectNode envelope(ContextSnapshot snapshot) {
        ObjectNode result = NODES.objectNode();
        result.put("snapshotId", snapshot.id().toString());
        result.put("asOf", snapshot.asOf().toString());
        return result;
    }

    private static ToolOutcome notFound(String what) {
        return ToolOutcome.failed(ToolOutcome.ToolError.NOT_FOUND, "No such " + what + " in this analysis.");
    }

    private static Instant instant(JsonNode node) {
        return node == null || node.isNull() ? null : Instant.parse(node.asString());
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }
}
