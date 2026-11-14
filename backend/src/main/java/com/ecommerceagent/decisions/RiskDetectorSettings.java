package com.ecommerceagent.decisions;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Detector rule {@code inventory-risk/1} (ADR-0011): risk is days of cover below {@code thresholdDays}; an
 * episode opens after {@code minObservations} consecutive current risk observations at least {@code minGap}
 * apart. Store-specific values arrive with the Store Business Profile.
 */
@ConfigurationProperties("operator.inventory-guardian.risk")
public record RiskDetectorSettings(
        @DefaultValue("3") BigDecimal thresholdDays,
        @DefaultValue("2") int minObservations,
        @DefaultValue("5m") Duration minGap,
        @DefaultValue("120s") Duration maxStockAge) {

    public static final String VERSION = "inventory-risk/1";
}
