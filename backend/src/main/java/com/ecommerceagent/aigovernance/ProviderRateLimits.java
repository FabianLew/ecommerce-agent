package com.ecommerceagent.aigovernance;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * Parameter set {@code ai-provider-limits} (ADR-0027 section 6.2): how many requests and input and output tokens
 * per minute, and how many calls in flight, the platform sends to one provider model. The defaults apply to every
 * model; {@code models} overrides them per {@code provider/model}. {@code defaultPause} is how long a model is paused
 * after the provider answered with rate limiting without a retry-after, and {@code maxPause} caps any pause. A
 * changed default needs a new version ({@code ParameterSetVersionTest}); the cloud profile refuses an override that
 * keeps the default version. Token counts are the reserved maxima of each call.
 */
@ConfigurationProperties(ProviderRateLimits.PREFIX)
public record ProviderRateLimits(
        @DefaultValue(DEFAULT_VERSION) String version,
        @DefaultValue("50") int requestsPerMinute,
        @DefaultValue("400000") int inputTokensPerMinute,
        @DefaultValue("80000") int outputTokensPerMinute,
        @DefaultValue("8") int maxParallel,
        @DefaultValue("30s") Duration defaultPause,
        @DefaultValue("10m") Duration maxPause,
        @DefaultValue Map<String, ModelLimit> models) {

    public static final String DEFAULT_VERSION = "ai-provider-limits/1";

    public static final String PREFIX = "operator.ai-provider-limits";

    public ProviderRateLimits {
        if (version == null || !version.matches("ai-provider-limits/[0-9]+")) {
            throw new IllegalArgumentException(PREFIX + ".version must look like ai-provider-limits/N, was " + version);
        }
        new ModelLimit(requestsPerMinute, inputTokensPerMinute, outputTokensPerMinute, maxParallel);
        if (defaultPause == null || maxPause == null || defaultPause.isNegative() || defaultPause.isZero()
                || maxPause.compareTo(defaultPause) < 0 || maxPause.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(PREFIX + " pauses must be positive, default <= max <= 1h");
        }
        models = Map.copyOf(new TreeMap<>(models));
    }

    /** The limits of one provider model. */
    public record ModelLimit(int requestsPerMinute, int inputTokensPerMinute, int outputTokensPerMinute,
            int maxParallel) {

        public ModelLimit {
            if (requestsPerMinute < 1 || inputTokensPerMinute < 1 || outputTokensPerMinute < 1 || maxParallel < 1) {
                throw new IllegalArgumentException(PREFIX + " limits must be at least 1");
            }
        }
    }

    public static ProviderRateLimits defaults() {
        return new Binder(List.of()).bindOrCreate(PREFIX, ProviderRateLimits.class);
    }

    public ModelLimit limitOf(String provider, String model) {
        return models.getOrDefault(provider + "/" + model,
                new ModelLimit(requestsPerMinute, inputTokensPerMinute, outputTokensPerMinute, maxParallel));
    }

    /** A pause after rate limiting: the provider's retry-after when it gave one, capped by {@code maxPause}. */
    public Duration pause(Duration retryAfter) {
        Duration pause = retryAfter == null || retryAfter.isNegative() || retryAfter.isZero() ? defaultPause
                : retryAfter;
        return pause.compareTo(maxPause) > 0 ? maxPause : pause;
    }

    /** The parameters in a fixed order. */
    public Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("version", version);
        parameters.put("requestsPerMinute", Integer.toString(requestsPerMinute));
        parameters.put("inputTokensPerMinute", Integer.toString(inputTokensPerMinute));
        parameters.put("outputTokensPerMinute", Integer.toString(outputTokensPerMinute));
        parameters.put("maxParallel", Integer.toString(maxParallel));
        parameters.put("defaultPause", defaultPause.toString());
        parameters.put("maxPause", maxPause.toString());
        new TreeMap<>(models).forEach((model, limit) -> parameters.put("models." + model,
                limit.requestsPerMinute() + "/" + limit.inputTokensPerMinute() + "/" + limit.outputTokensPerMinute()
                        + "/" + limit.maxParallel()));
        return parameters;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProviderRateLimits.class)
    static class Registration {
    }
}
