package com.ecommerceagent.integration;

import static com.ecommerceagent.integration.WooPayloads.backorders;
import static com.ecommerceagent.integration.WooPayloads.emptyToNull;
import static com.ecommerceagent.integration.WooPayloads.gmt;
import static com.ecommerceagent.integration.WooPayloads.stockQuantity;
import static com.ecommerceagent.integration.WooPayloads.stockStatus;
import static com.ecommerceagent.integration.WooPayloads.text;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.CommerceSource;
import com.ecommerceagent.commerce.InventoryReading;
import com.ecommerceagent.commerce.InventoryRepository;
import com.ecommerceagent.commerce.ObservationSource;
import com.ecommerceagent.commerce.OrderData;
import com.ecommerceagent.commerce.OrderLineData;
import com.ecommerceagent.commerce.OrderRepository;
import com.ecommerceagent.commerce.ProductData;
import com.ecommerceagent.commerce.ReservationSemantics;
import com.ecommerceagent.commerce.StockChange;
import com.ecommerceagent.commerce.StockTracking;
import com.ecommerceagent.commerce.Variant;
import com.ecommerceagent.commerce.VariantData;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.WebhookInboxRepository.InboxStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies one minimized webhook payload to the commerce model and writes the resulting outbox events, in the
 * caller's transaction (ADR-0009). Stock never moves back in source time (IG-04).
 */
@Component
class WooWebhookNormalizer {

    private static final CommerceSource SOURCE = CommerceSource.WOOCOMMERCE;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CatalogRepository catalog;
    private final InventoryRepository inventory;
    private final OrderRepository orders;
    private final OutboxEvents outbox;
    private final Clock clock;

    WooWebhookNormalizer(CatalogRepository catalog, InventoryRepository inventory, OrderRepository orders,
            OutboxEvents outbox, Clock clock) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
    }

    Result apply(TrustedStoreScope scope, UUID inboxId, String topic, String payloadJson) {
        JsonNode payload = JSON.readTree(payloadJson);
        if (topic.startsWith("order.")) {
            return order(scope, payload);
        }
        return product(scope, inboxId, payload);
    }

    private Result order(TrustedStoreScope scope, JsonNode payload) {
        OrderData order = WooPayloads.order(payload);
        if (!orders.upsert(scope, SOURCE, order)) {
            return new Result(InboxStatus.IGNORED, "ORDER_STALE");
        }
        Set<String> variantIds = new LinkedHashSet<>();
        for (OrderLineData line : order.lines()) {
            if (line.variantExternalId() != null) {
                catalog.variantByExternalId(scope, SOURCE, line.variantExternalId())
                        .ifPresent(variant -> variantIds.add(variant.id().toString()));
            }
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("orderExternalId", order.externalId());
        event.put("status", order.status());
        event.put("sourceUpdatedAt", string(order.sourceUpdatedAt()));
        event.put("variantIds", List.copyOf(variantIds));
        // The same order state delivered twice (order.created and order.updated) is one event.
        outbox.append(scope, OutboxEvents.ORDER_CHANGED, "order", order.externalId(), event,
                "order:" + order.externalId() + ":" + order.sourceUpdatedAt() + ":" + order.status());
        return new Result(InboxStatus.PROCESSED, "ORDER_UPSERTED");
    }

    private Result product(TrustedStoreScope scope, UUID inboxId, JsonNode payload) {
        String externalId = text(payload, "id");
        String type = text(payload, "type");
        Instant modified = gmt(payload, "date_modified_gmt");
        String name = text(payload, "name");
        String status = text(payload, "status") == null ? "unknown" : text(payload, "status");

        if ("variation".equals(type)) {
            String parentId = text(payload, "parent_id");
            if (parentId == null || catalog.productIdByExternalId(scope, SOURCE, parentId).isEmpty()) {
                verify(scope, inboxId, "product", parentId == null ? externalId : parentId, "UNKNOWN_PRODUCT");
                return new Result(InboxStatus.PROCESSED, "VERIFY_UNKNOWN_PRODUCT");
            }
            StockTracking tracking = WooPayloads.variationTracking(payload);
            String variantName = catalog.variantByExternalId(scope, SOURCE, externalId).map(Variant::name)
                    .orElse(name == null ? "" : name);
            UUID variantId = catalog.upsertVariant(scope, SOURCE, new VariantData(externalId, parentId, variantName,
                    emptyToNull(text(payload, "sku")), status, tracking, backorders(payload), modified));
            if (tracking == StockTracking.PARENT) {
                // Shared stock arrives with the parent product's payload.
                return new Result(InboxStatus.PROCESSED, "CATALOG_ONLY");
            }
            return result(List.of(stock(scope, inboxId, variantId, payload, tracking, modified)));
        }

        String productType = type == null ? "unknown" : type;
        UUID productId = catalog.upsertProduct(scope, SOURCE, new ProductData(externalId, name == null ? "" : name,
                emptyToNull(text(payload, "sku")), productType, status, modified));
        if ("variable".equals(productType)) {
            if (!payload.path("manage_stock").asBoolean(false)) {
                return new Result(InboxStatus.PROCESSED, "CATALOG_ONLY");
            }
            List<String> outcomes = new ArrayList<>();
            for (Variant variant : catalog.variantsOfProduct(scope, productId)) {
                if (variant.stockTracking() == StockTracking.PARENT) {
                    outcomes.add(stock(scope, inboxId, variant.id(), payload, StockTracking.PARENT, modified));
                }
            }
            return outcomes.isEmpty() ? new Result(InboxStatus.PROCESSED, "CATALOG_ONLY") : result(outcomes);
        }
        StockTracking tracking = WooPayloads.productTracking(payload);
        UUID variantId = catalog.upsertVariant(scope, SOURCE, new VariantData(externalId, externalId,
                name == null ? "" : name, emptyToNull(text(payload, "sku")), status, tracking, backorders(payload),
                modified));
        return result(List.of(stock(scope, inboxId, variantId, payload, tracking, modified)));
    }

    private String stock(TrustedStoreScope scope, UUID inboxId, UUID variantId, JsonNode stockSource,
            StockTracking tracking, Instant modified) {
        if (!stockSource.has("stock_status")) {
            // Legacy (v1/v2) webhook payloads do not carry the stock status we need.
            verify(scope, inboxId, "variant", variantId.toString(), "UNSUPPORTED_PAYLOAD");
            return "VERIFY_UNSUPPORTED_PAYLOAD";
        }
        Instant observedAt = clock.instant();
        // Woo stock already includes on-hold/processing/completed orders (ADR-0008).
        InventoryReading reading = new InventoryReading(stockQuantity(stockSource, tracking), stockStatus(stockSource),
                ReservationSemantics.ALREADY_ACCOUNTED_FOR, null, ObservationSource.WEBHOOK, modified, observedAt);
        StockChange change = inventory.recordIfNewer(scope, variantId, reading);
        switch (change) {
            case RECORDED -> {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("variantId", variantId.toString());
                event.put("stockQuantity", reading.stockQuantity());
                event.put("stockStatus", reading.stockStatus().name());
                event.put("sourceUpdatedAt", string(modified));
                event.put("observedAt", observedAt.toString());
                event.put("observationSource", ObservationSource.WEBHOOK.name());
                outbox.append(scope, OutboxEvents.INVENTORY_CHANGED, "variant", variantId.toString(), event,
                        "inventory:" + variantId + ":" + modified);
            }
            case AMBIGUOUS -> verify(scope, inboxId, "variant", variantId.toString(), "AMBIGUOUS_SOURCE_TIME");
            case STALE, DUPLICATE -> {
                // Nothing newer than what we know: no state change and no event.
            }
        }
        return change.name();
    }

    private void verify(TrustedStoreScope scope, UUID inboxId, String aggregateType, String aggregateId,
            String reason) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("reason", reason);
        event.put(aggregateType.equals("variant") ? "variantId" : "productExternalId", aggregateId);
        outbox.append(scope, OutboxEvents.INVENTORY_VERIFICATION_REQUESTED, aggregateType, aggregateId, event,
                "verify:" + inboxId + ":" + aggregateType + ":" + aggregateId);
    }

    private static Result result(List<String> outcomes) {
        boolean nothingNew = outcomes.stream()
                .allMatch(o -> o.equals(StockChange.STALE.name()) || o.equals(StockChange.DUPLICATE.name()));
        return new Result(nothingNew ? InboxStatus.IGNORED : InboxStatus.PROCESSED, String.join(",", outcomes));
    }

    private static String string(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    record Result(InboxStatus status, String outcome) {
    }
}
