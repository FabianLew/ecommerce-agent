package com.ecommerceagent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * Parameter set {@code api-rate-limits} (ADR-0027 section 6.1): requests per minute that start work, per user, per
 * store and per endpoint; webhook deliveries per minute per endpoint key; how long an idempotent answer is kept. A
 * changed default needs a new version ({@code ParameterSetVersionTest}); the cloud profile refuses an override that
 * keeps the default version.
 */
@ConfigurationProperties(ApiRateLimits.PREFIX)
public record ApiRateLimits(
        @DefaultValue(DEFAULT_VERSION) String version,
        @DefaultValue("20") int workPerUserPerMinute,
        @DefaultValue("60") int workPerStorePerMinute,
        @DefaultValue("600") int workPerEndpointPerMinute,
        @DefaultValue("600") int webhooksPerEndpointKeyPerMinute,
        @DefaultValue("24h") Duration idempotencyRetention) {

    public static final String DEFAULT_VERSION = "api-rate-limits/1";

    public static final String PREFIX = "operator.api-rate-limits";

    public ApiRateLimits {
        if (version == null || !version.matches("api-rate-limits/[0-9]+")) {
            throw new IllegalArgumentException(PREFIX + ".version must look like api-rate-limits/N, was " + version);
        }
        if (workPerUserPerMinute < 1 || workPerStorePerMinute < 1 || workPerEndpointPerMinute < 1
                || webhooksPerEndpointKeyPerMinute < 1) {
            throw new IllegalArgumentException(PREFIX + " limits must be at least 1");
        }
        if (idempotencyRetention == null || idempotencyRetention.compareTo(Duration.ofMinutes(10)) < 0
                || idempotencyRetention.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException(PREFIX + ".idempotency-retention must be 10 minutes to 7 days");
        }
    }

    public static ApiRateLimits defaults() {
        return new Binder(List.of()).bindOrCreate(PREFIX, ApiRateLimits.class);
    }

    /** The parameters in a fixed order. */
    public Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("version", version);
        parameters.put("workPerUserPerMinute", Integer.toString(workPerUserPerMinute));
        parameters.put("workPerStorePerMinute", Integer.toString(workPerStorePerMinute));
        parameters.put("workPerEndpointPerMinute", Integer.toString(workPerEndpointPerMinute));
        parameters.put("webhooksPerEndpointKeyPerMinute", Integer.toString(webhooksPerEndpointKeyPerMinute));
        parameters.put("idempotencyRetention", idempotencyRetention.toString());
        return parameters;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ApiRateLimits.class)
    static class Registration {
    }
}
