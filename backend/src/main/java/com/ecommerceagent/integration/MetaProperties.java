package com.ecommerceagent.integration;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Meta Graph API settings (ADR-0017). The base URL is fixed to Meta's host; only the {@code local} and {@code test}
 * profiles may point it at a stub, which {@link IntegrationConfiguration} enforces at start.
 */
@ConfigurationProperties("operator.integration.meta")
record MetaProperties(
        @DefaultValue("https://graph.facebook.com") URI graphBaseUrl,
        @DefaultValue("v23.0") String apiVersion,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("20s") Duration timeout,
        @DefaultValue("5000000") int maxResponseBytes,
        @DefaultValue("30s") Duration pollInterval,
        @DefaultValue("60s") Duration minBackoff) {

    static final String META_HOST = "graph.facebook.com";

    MetaProperties {
        if (apiVersion == null || !apiVersion.matches("v[0-9]{1,3}\\.[0-9]{1,3}")) {
            throw new IllegalStateException("operator.integration.meta.api-version must look like v23.0");
        }
    }

    boolean usesMetaHost() {
        return "https".equals(graphBaseUrl.getScheme()) && META_HOST.equals(graphBaseUrl.getHost())
                && graphBaseUrl.getPort() == -1 && (graphBaseUrl.getPath() == null || graphBaseUrl.getPath().isEmpty()
                        || "/".equals(graphBaseUrl.getPath()));
    }
}
