package com.ecommerceagent.integration;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ecommerceagent.integration.woocommerce.WooClientException;
import com.ecommerceagent.integration.woocommerce.WooCommerceClient;
import com.ecommerceagent.integration.woocommerce.WooCredentials;
import com.ecommerceagent.integration.woocommerce.WooResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only check of a WooCommerce connection: products, orders and the currency setting (ADR-0007). */
@Component
class WooConnectionChecker {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WooCommerceClient client;
    private final Clock clock;

    WooConnectionChecker(WooCommerceClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    ConnectionCheck check(URI storeUrl, WooCredentials credentials, String storeCurrency) {
        try {
            for (String resource : List.of("products", "orders")) {
                WooResponse response = client.get(storeUrl, credentials, resource,
                        Map.of("per_page", "1", "_fields", "id"));
                ConnectionCheckError error = classifyList(response);
                if (error != null) {
                    return failed(error);
                }
            }
            List<ConnectionWarning> warnings = new ArrayList<>();
            String sourceCurrency = currency(client.get(storeUrl, credentials,
                    "settings/general/woocommerce_currency", Map.of()));
            if (sourceCurrency == null) {
                warnings.add(ConnectionWarning.CURRENCY_UNKNOWN);
            } else if (!sourceCurrency.equalsIgnoreCase(storeCurrency)) {
                warnings.add(ConnectionWarning.CURRENCY_MISMATCH);
            }
            return new ConnectionCheck(ConnectionStatus.CONNECTED, null, warnings, sourceCurrency, clock.instant());
        } catch (WooClientException e) {
            return failed(switch (e.kind()) {
                case ADDRESS_NOT_ALLOWED -> ConnectionCheckError.ADDRESS_NOT_ALLOWED;
                case UNREACHABLE -> ConnectionCheckError.UNREACHABLE;
                case TLS_ERROR -> ConnectionCheckError.TLS_ERROR;
                case RESPONSE_TOO_LARGE -> ConnectionCheckError.UNEXPECTED_RESPONSE;
            });
        }
    }

    /** Null when the response is a JSON list from the WooCommerce API. */
    static ConnectionCheckError classifyList(WooResponse response) {
        if (response.isSuccess()) {
            return response.isJson() && isJsonArray(response.body()) ? null : ConnectionCheckError.NOT_WOOCOMMERCE;
        }
        return switch (response.status()) {
            case 401 -> ConnectionCheckError.AUTH_FAILED;
            case 403 -> ConnectionCheckError.PERMISSION_DENIED;
            case 404 -> ConnectionCheckError.NOT_WOOCOMMERCE;
            default -> ConnectionCheckError.UNEXPECTED_RESPONSE;
        };
    }

    private static String currency(WooResponse response) {
        if (!response.isSuccess() || !response.isJson()) {
            return null;
        }
        try {
            JsonNode value = JSON.readTree(response.body()).get("value");
            if (value == null || !value.isString()) {
                return null;
            }
            String code = value.asString().strip().toUpperCase(Locale.ROOT);
            return code.matches("[A-Z]{3}") ? code : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isJsonArray(String body) {
        try {
            return JSON.readTree(body).isArray();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private ConnectionCheck failed(ConnectionCheckError error) {
        return new ConnectionCheck(ConnectionStatus.ERROR, error, List.of(), null, clock.instant());
    }
}
