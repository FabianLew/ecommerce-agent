package com.ecommerceagent.aigovernance;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * {@code operator.ai-governance} (ADR-0027): the safety margin added to estimated input tokens, how old a price
 * verification may be before the price is refused, and how long a reservation may stay open before it is overdue.
 */
@ConfigurationProperties(prefix = "operator.ai-governance", ignoreUnknownFields = false)
public record AiGovernanceSettings(
        @DefaultValue("0.25") BigDecimal inputTokenMargin,
        @DefaultValue("90d") Duration priceMaxVerificationAge,
        @DefaultValue("15m") Duration reservationTimeout) {

    public AiGovernanceSettings {
        Objects.requireNonNull(inputTokenMargin, "inputTokenMargin");
        if (inputTokenMargin.signum() < 0) {
            throw new IllegalArgumentException("operator.ai-governance.input-token-margin must not be negative");
        }
        if (priceMaxVerificationAge.isNegative() || priceMaxVerificationAge.isZero()
                || reservationTimeout.isNegative() || reservationTimeout.isZero()) {
            throw new IllegalArgumentException("operator.ai-governance durations must be positive");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiGovernanceSettings.class)
    static class Registration {
    }
}
