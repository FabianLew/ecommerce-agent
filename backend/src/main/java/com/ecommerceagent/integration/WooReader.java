package com.ecommerceagent.integration;

import java.util.Map;

import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.woocommerce.WooClientException;
import com.ecommerceagent.integration.woocommerce.WooCommerceClient;
import com.ecommerceagent.integration.woocommerce.WooCredentials;
import com.ecommerceagent.integration.woocommerce.WooResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Read-only WooCommerce calls of a store's connected integration, with errors mapped to import errors. */
@Component
class WooReader {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final IntegrationConnections connections;
    private final IntegrationConnectionRepository connectionRepository;
    private final WooCommerceClient client;

    WooReader(IntegrationConnections connections, IntegrationConnectionRepository connectionRepository,
            WooCommerceClient client) {
        this.connections = connections;
        this.connectionRepository = connectionRepository;
        this.client = client;
    }

    /** The store's connected WooCommerce integration with its credentials. */
    Source source(TrustedStoreScope scope) {
        IntegrationConnection connection = connectionRepository.findByProvider(scope, IntegrationProvider.WOOCOMMERCE)
                .filter(found -> found.status() == ConnectionStatus.CONNECTED)
                .orElseThrow(() -> new WooImportException(ConnectionCheckError.AUTH_FAILED,
                        "No connected WooCommerce integration"));
        return new Source(connection, connections.wooCredentials(scope, connection));
    }

    /** A successful JSON response, or null when the resource no longer exists in the store (404). */
    WooResponse response(Source source, String resource, Map<String, String> query) {
        WooResponse response;
        try {
            response = client.get(source.connection().storeUrl(), source.credentials(), resource, query);
        } catch (WooClientException e) {
            throw new WooImportException(switch (e.kind()) {
                case ADDRESS_NOT_ALLOWED -> ConnectionCheckError.ADDRESS_NOT_ALLOWED;
                case UNREACHABLE -> ConnectionCheckError.UNREACHABLE;
                case TLS_ERROR -> ConnectionCheckError.TLS_ERROR;
                case RESPONSE_TOO_LARGE -> ConnectionCheckError.UNEXPECTED_RESPONSE;
            }, e.getMessage());
        }
        if (response.status() == 404 && response.isJson()) {
            return null;
        }
        if (!response.isSuccess() || !response.isJson()) {
            ConnectionCheckError error = WooConnectionChecker.classifyList(response);
            throw new WooImportException(error == null ? ConnectionCheckError.UNEXPECTED_RESPONSE : error,
                    "GET " + resource + " returned " + response.status());
        }
        return response;
    }

    JsonNode json(Source source, String resource, Map<String, String> query) {
        WooResponse response = response(source, resource, query);
        return response == null ? null : JSON.readTree(response.body());
    }

    static JsonNode body(WooResponse response) {
        return JSON.readTree(response.body());
    }

    /** The REST variations endpoint omits type and parent, which the normalizer needs. */
    static JsonNode asVariation(JsonNode variation, String productId, String name) {
        ObjectNode copy = WooPayloads.minimizeProduct(variation);
        copy.put("type", "variation");
        copy.put("parent_id", Long.parseLong(productId));
        if (!copy.hasNonNull("name")) {
            copy.put("name", name);
        }
        return copy;
    }

    record Source(IntegrationConnection connection, WooCredentials credentials) {
    }
}
