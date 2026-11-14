package com.ecommerceagent.integration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Integration settings (ADR-0007). {@code secretsKey} is a base64 AES-256 key; the application does not
 * start without it. The WooCommerce flags relax the address policy and are enabled only in profile
 * {@code local}.
 */
@ConfigurationProperties("operator.integration")
record IntegrationProperties(String secretsKey, @DefaultValue("v1") String secretsKeyId, WooCommerce woocommerce,
        Webhooks webhooks) {

    IntegrationProperties {
        if (woocommerce == null) {
            woocommerce = new WooCommerce(false, false, Duration.ofSeconds(5), Duration.ofSeconds(30), 5_000_000);
        }
        if (webhooks == null) {
            webhooks = new Webhooks(null);
        }
    }

    record WooCommerce(
            @DefaultValue("false") boolean allowInsecureHttp,
            @DefaultValue("false") boolean allowPrivateNetwork,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration responseTimeout,
            @DefaultValue("5000000") int maxResponseBytes) {
    }

    /**
     * Webhooks (ADR-0009). {@code baseUrl} is the public address of the API that shops deliver to, used to
     * show the delivery URL; without it the UI shows no URL.
     */
    record Webhooks(String baseUrl) {
    }
}
