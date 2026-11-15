package com.ecommerceagent.decisions;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parameter set {@code inventory-risk} of the detector (ADR-0011, ADR-0026 point 6): risk is days of cover below
 * {@code thresholdDays}; an episode opens after {@code minObservations} consecutive current risk observations at
 * least {@code minGap} apart; cover below {@code warningDays} gives the L0 stock warning. Every observation records
 * the version and the parameters. A changed default needs a new version ({@code ParameterSetVersionTest}).
 */
@ConfigurationProperties("operator.inventory-guardian.risk")
public record RiskDetectorSettings(
        @DefaultValue(DEFAULT_VERSION) String version,
        @DefaultValue("3") BigDecimal thresholdDays,
        @DefaultValue("5") BigDecimal warningDays,
        @DefaultValue("2") int minObservations,
        @DefaultValue("5m") Duration minGap,
        @DefaultValue("120s") Duration maxStockAge) {

    public static final String DEFAULT_VERSION = "inventory-risk/2";

    public static final String PREFIX = "operator.inventory-guardian.risk";

    public RiskDetectorSettings {
        if (version == null || !version.matches("inventory-risk/[0-9]+")) {
            throw new IllegalArgumentException(PREFIX + ".version must look like inventory-risk/N, was " + version);
        }
        if (warningDays.compareTo(thresholdDays) < 0) {
            throw new IllegalArgumentException(PREFIX + ".warning-days must not be below threshold-days");
        }
    }

    /** The parameters as recorded with every observation, in a fixed order. */
    public Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("version", version);
        parameters.put("thresholdDays", thresholdDays.stripTrailingZeros().toPlainString());
        parameters.put("warningDays", warningDays.stripTrailingZeros().toPlainString());
        parameters.put("minObservations", Integer.toString(minObservations));
        parameters.put("minGap", minGap.toString());
        parameters.put("maxStockAge", maxStockAge.toString());
        return parameters;
    }
}
