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
record IntegrationProperties(String secretsKey, @DefaultValue("v1") String secretsKeyId, WooCommerce woocommerce) {

    IntegrationProperties {
        if (woocommerce == null) {
            woocommerce = new WooCommerce(false, false, Duration.ofSeconds(5), Duration.ofSeconds(10), 5_000_000);
        }
    }

    record WooCommerce(
            @DefaultValue("false") boolean allowInsecureHttp,
            @DefaultValue("false") boolean allowPrivateNetwork,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout,
            @DefaultValue("5000000") int maxResponseBytes) {
    }
}
