package com.ecommerceagent.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.ecommerceagent.commerce.Backorders;
import com.ecommerceagent.commerce.OrderData;
import com.ecommerceagent.commerce.OrderLineData;
import com.ecommerceagent.commerce.StockStatus;
import com.ecommerceagent.commerce.StockTracking;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Mapping of WooCommerce REST (v3) JSON to commerce data, shared by the import (ADR-0008) and webhooks
 * (ADR-0009), plus the field allowlists that keep customer data out of our database.
 */
final class WooPayloads {

    static final Set<String> PRODUCT_FIELDS = Set.of("id", "parent_id", "name", "sku", "type", "status",
            "date_modified_gmt", "manage_stock", "stock_quantity", "stock_status", "backorders", "attributes");
    /** No billing, shipping, customer, IP, user agent or note fields (ADR-0008). */
    static final Set<String> ORDER_FIELDS = Set.of("id", "status", "currency", "total", "total_tax",
            "discount_total", "shipping_total", "date_created_gmt", "date_paid_gmt", "date_completed_gmt",
            "date_modified_gmt", "line_items");
    /** Line item meta data may hold personalised text, so only these fields are kept. */
    static final Set<String> ORDER_LINE_FIELDS = Set.of("id", "product_id", "variation_id", "sku", "quantity",
            "total", "total_tax");
    static final Set<String> ATTRIBUTE_FIELDS = Set.of("name", "option");

    private static final DateTimeFormatter WOO_DATE = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private WooPayloads() {
    }

    /** A copy of a product or variation with only allowlisted fields. */
    static ObjectNode minimizeProduct(JsonNode product) {
        ObjectNode copy = pick(product, PRODUCT_FIELDS);
        if (copy.has("attributes")) {
            copy.set("attributes", pickEach(product.path("attributes"), ATTRIBUTE_FIELDS));
        }
        return copy;
    }

    /** A copy of an order without customer data. */
    static ObjectNode minimizeOrder(JsonNode order) {
        ObjectNode copy = pick(order, ORDER_FIELDS);
        if (copy.has("line_items")) {
            copy.set("line_items", pickEach(order.path("line_items"), ORDER_LINE_FIELDS));
        }
        return copy;
    }

    static OrderData order(JsonNode order) {
        List<OrderLineData> lines = new ArrayList<>();
        for (JsonNode line : order.path("line_items")) {
            String productId = text(line, "product_id");
            String variationId = text(line, "variation_id");
            String variantId = variationId == null || "0".equals(variationId) ? productId : variationId;
            lines.add(new OrderLineData(text(line, "id"), "0".equals(variantId) ? null : variantId,
                    "0".equals(productId) ? null : productId, emptyToNull(text(line, "sku")),
                    line.path("quantity").asInt(0), money(line, "total"), money(line, "total_tax")));
        }
        Instant placedAt = gmt(order, "date_created_gmt");
        if (placedAt == null) {
            throw new WooImportException(ConnectionCheckError.UNEXPECTED_RESPONSE, "Order without creation date");
        }
        String status = text(order, "status");
        String currency = text(order, "currency");
        if (status == null || currency == null) {
            throw new WooImportException(ConnectionCheckError.UNEXPECTED_RESPONSE, "Order without status or currency");
        }
        return new OrderData(text(order, "id"), status, currency, money(order, "total"),
                money(order, "total_tax"), money(order, "discount_total"), money(order, "shipping_total"), placedAt,
                gmt(order, "date_paid_gmt"), gmt(order, "date_completed_gmt"), gmt(order, "date_modified_gmt"),
                lines);
    }

    /** Stock tracking of a variation: own stock, the parent's shared stock, or none. */
    static StockTracking variationTracking(JsonNode variation) {
        JsonNode manage = variation.path("manage_stock");
        if (manage.isBoolean()) {
            return manage.asBoolean() ? StockTracking.VARIANT : StockTracking.NONE;
        }
        return "parent".equals(manage.asString(null)) ? StockTracking.PARENT : StockTracking.NONE;
    }

    /** Stock tracking of a non-variable product, which is its own variant. */
    static StockTracking productTracking(JsonNode product) {
        return product.path("manage_stock").asBoolean(false) ? StockTracking.VARIANT : StockTracking.NONE;
    }

    static Integer stockQuantity(JsonNode stockSource, StockTracking tracking) {
        JsonNode quantity = stockSource.path("stock_quantity");
        return tracking != StockTracking.NONE && quantity.isNumber() ? quantity.asInt() : null;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.isString() ? value.asString() : value.toString();
    }

    static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** WooCommerce *_gmt fields are UTC without an offset, e.g. {@code 2026-11-14T10:00:00}. */
    static Instant gmt(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, WOO_DATE).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new WooImportException(ConnectionCheckError.UNEXPECTED_RESPONSE, "Unreadable date in " + field);
        }
    }

    static String wooDate(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).withNano(0).format(WOO_DATE);
    }

    static BigDecimal money(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new WooImportException(ConnectionCheckError.UNEXPECTED_RESPONSE, "Unreadable amount in " + field);
        }
    }

    static StockStatus stockStatus(JsonNode node) {
        return switch (String.valueOf(text(node, "stock_status"))) {
            case "outofstock" -> StockStatus.OUT_OF_STOCK;
            case "onbackorder" -> StockStatus.ON_BACKORDER;
            default -> StockStatus.IN_STOCK;
        };
    }

    static Backorders backorders(JsonNode node) {
        return switch (String.valueOf(text(node, "backorders"))) {
            case "notify" -> Backorders.NOTIFY;
            case "yes" -> Backorders.YES;
            default -> Backorders.NO;
        };
    }

    static String variationName(String productName, JsonNode variation) {
        List<String> options = new ArrayList<>();
        for (JsonNode attribute : variation.path("attributes")) {
            String option = text(attribute, "option");
            if (option != null) {
                options.add(option);
            }
        }
        String base = productName == null ? "" : productName;
        return options.isEmpty() ? base : base + " – " + String.join(", ", options);
    }

    private static ObjectNode pick(JsonNode source, Set<String> fields) {
        ObjectNode copy = JsonNodeFactory.instance.objectNode();
        for (String field : fields) {
            JsonNode value = source.get(field);
            if (value != null) {
                copy.set(field, value);
            }
        }
        return copy;
    }

    private static JsonNode pickEach(JsonNode items, Set<String> fields) {
        var array = JsonNodeFactory.instance.arrayNode();
        for (JsonNode item : items) {
            array.add(pick(item, fields));
        }
        return array;
    }
}
