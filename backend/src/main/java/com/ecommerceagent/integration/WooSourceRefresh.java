package com.ecommerceagent.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.StockTracking;
import com.ecommerceagent.commerce.Variant;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.WooReader.Source;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Fresh, authoritative reads of single products and variants from WooCommerce (ADR-0010): settle a request for
 * verification and refresh catalog entries that a webhook referred to but we did not know.
 */
@Service
public class WooSourceRefresh {

    static final String PRODUCT_FIELDS = String.join(",", WooPayloads.PRODUCT_FIELDS);

    private final WooReader reader;
    private final CatalogRepository catalog;
    private final WooPayloadNormalizer normalizer;
    private final TransactionTemplate transactions;

    WooSourceRefresh(WooReader reader, CatalogRepository catalog, WooPayloadNormalizer normalizer,
            TransactionTemplate transactions) {
        this.reader = reader;
        this.catalog = catalog;
        this.normalizer = normalizer;
        this.transactions = transactions;
    }

    /** Reads the variant's current stock (or its parent's, when shared) and records it. */
    public String refreshVariant(TrustedStoreScope scope, UUID variantId) {
        Variant variant = catalog.variant(scope, variantId)
                .orElseThrow(() -> new IntegrationNotFoundException(variantId));
        String productId = catalog.productExternalId(scope, variant.productId()).orElseThrow();
        Source source = source(scope);
        boolean ownProduct = variant.externalId().equals(productId) || variant.stockTracking() == StockTracking.PARENT;
        JsonNode payload = get(source, ownProduct
                ? "products/" + productId
                : "products/" + productId + "/variations/" + variant.externalId());
        if (payload == null || !payload.isObject()) {
            return "SOURCE_NOT_FOUND";
        }
        if (!ownProduct) {
            payload = WooReader.asVariation(payload, productId, variant.name());
        }
        String readId = "read:" + UUID.randomUUID();
        JsonNode read = payload;
        return transactions.execute(status -> normalizer.applyFresh(scope, readId, read).outcome());
    }

    /** Reads products (with all variations of variable ones) and records catalog and stock. */
    public List<String> refreshProducts(TrustedStoreScope scope, List<String> productExternalIds) {
        Source source = source(scope);
        List<String> outcomes = new ArrayList<>();
        for (String productId : productExternalIds) {
            if (!productId.matches("\\d{1,20}")) {
                continue;
            }
            JsonNode product = get(source, "products/" + productId);
            if (product == null || !product.isObject()) {
                outcomes.add(productId + ":SOURCE_NOT_FOUND");
                continue;
            }
            List<JsonNode> variations = new ArrayList<>();
            if ("variable".equals(WooPayloads.text(product, "type"))) {
                for (int page = 1; ; page++) {
                    JsonNode items = get(source, "products/" + productId + "/variations", Map.of("per_page", "100",
                            "page", Integer.toString(page), "orderby", "id", "order", "asc"));
                    if (items == null || !items.isArray()) {
                        break;
                    }
                    String parentName = WooPayloads.text(product, "name");
                    items.forEach(item -> variations.add(
                            WooReader.asVariation(item, productId, WooPayloads.variationName(parentName, item))));
                    if (items.size() < 100) {
                        break;
                    }
                }
            }
            String readId = "read:" + UUID.randomUUID();
            outcomes.add(transactions.execute(status -> {
                StringBuilder outcome = new StringBuilder(normalizer.applyFresh(scope, readId, product).outcome());
                for (JsonNode variation : variations) {
                    outcome.append(';').append(normalizer.applyFresh(scope, readId, variation).outcome());
                }
                return productId + ":" + outcome;
            }));
        }
        return outcomes;
    }

    private JsonNode get(Source source, String resource) {
        return get(source, resource, Map.of());
    }

    private JsonNode get(Source source, String resource, Map<String, String> extra) {
        Map<String, String> query = new LinkedHashMap<>(extra);
        query.put("_fields", PRODUCT_FIELDS);
        return reader.json(source, resource, query);
    }

    private Source source(TrustedStoreScope scope) {
        return reader.source(scope);
    }
}
