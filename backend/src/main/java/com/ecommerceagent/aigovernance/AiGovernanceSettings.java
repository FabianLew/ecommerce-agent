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
 * verification may be before the price is refused, how long a reservation may stay open before the sweep closes it,
 * and after how long an {@code UNKNOWN} reservation is reconciled conservatively at its full reserved amount
 * ({@code unknown-conservative-settlement-enabled=false} leaves every {@code UNKNOWN} to a person), and how many
 * agent runs may be active on the whole platform at once ({@code max-concurrent-runs}, section 6.2), and how often,
 * how many and for how long uncertain calls are looked up with their providers ({@code reconciliation-*}), and how
 * often budgets are compared with the alert thresholds ({@code budget-alert-interval}).
 */
@ConfigurationProperties(prefix = "operator.ai-governance", ignoreUnknownFields = false)
public record AiGovernanceSettings(
        @DefaultValue("0.25") BigDecimal inputTokenMargin,
        @DefaultValue("90d") Duration priceMaxVerificationAge,
        @DefaultValue("15m") Duration reservationTimeout,
        @DefaultValue("1m") Duration sweepInterval,
        @DefaultValue("500") int sweepBatchSize,
        @DefaultValue("true") boolean unknownConservativeSettlementEnabled,
        @DefaultValue("24h") Duration unknownConservativeSettlementAfter,
        @DefaultValue("8") int maxConcurrentRuns,
        @DefaultValue("15m") Duration reconciliationInterval,
        @DefaultValue("100") int reconciliationBatchSize,
        @DefaultValue("30s") Duration reconciliationTimeLimit,
        @DefaultValue("5m") Duration budgetAlertInterval) {

    public AiGovernanceSettings {
        Objects.requireNonNull(inputTokenMargin, "inputTokenMargin");
        if (inputTokenMargin.signum() < 0) {
            throw new IllegalArgumentException("operator.ai-governance.input-token-margin must not be negative");
        }
        if (priceMaxVerificationAge.isNegative() || priceMaxVerificationAge.isZero()
                || reservationTimeout.isNegative() || reservationTimeout.isZero()) {
            throw new IllegalArgumentException("operator.ai-governance durations must be positive");
        }
        if (sweepInterval.isNegative() || sweepInterval.isZero() || sweepBatchSize < 1 || sweepBatchSize > 10_000) {
            throw new IllegalArgumentException("operator.ai-governance sweep settings are out of range");
        }
        if (maxConcurrentRuns < 1 || maxConcurrentRuns > 1_000) {
            throw new IllegalArgumentException("operator.ai-governance.max-concurrent-runs is out of range");
        }
        if (reconciliationInterval.compareTo(Duration.ofMinutes(1)) < 0 || reconciliationBatchSize < 1
                || reconciliationBatchSize > 1_000 || reconciliationTimeLimit.compareTo(Duration.ofSeconds(1)) < 0
                || reconciliationTimeLimit.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("operator.ai-governance reconciliation settings are out of range");
        }
        if (budgetAlertInterval.compareTo(Duration.ofSeconds(30)) < 0) {
            throw new IllegalArgumentException("operator.ai-governance.budget-alert-interval must be at least 30s");
        }
        if (unknownConservativeSettlementAfter.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException(
                    "operator.ai-governance.unknown-conservative-settlement-after must be at least 1 minute");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiGovernanceSettings.class)
    static class Registration {
    }
}
