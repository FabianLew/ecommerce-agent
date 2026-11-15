package com.ecommerceagent.automation.guardian;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.modulith.NamedInterface;

/**
 * Parameter set of policy {@code inventory-protection} (ADR-0012 point 5, ADR-0026 point 6), with an explicit
 * version recorded in every evaluation together with the parameters. The executor applies the limits of the
 * evaluation it was authorised under. A changed default needs a new version ({@code ParameterSetVersionTest}).
 * {@code mode} is the deployment's choice and is recorded separately. Per-store configuration arrives with the
 * policy screen.
 */
@ConfigurationProperties(InventoryProtectionPolicy.PREFIX)
@NamedInterface("guardian")
public record InventoryProtectionPolicy(
        @DefaultValue(DEFAULT_VERSION) String version,
        @DefaultValue("EVALUATION_ONLY") Mode mode,
        @DefaultValue("120s") Duration maxStockAge,
        @DefaultValue("60s") Duration maxAdStateAge,
        @DefaultValue("24h") Duration maxVerificationAge,
        @DefaultValue("3") int maxPausesPerStore24h,
        @DefaultValue("24h") Duration cooldownPerAd,
        @DefaultValue("L1") Autonomy autonomy,
        @DefaultValue("false") boolean l2Enabled) {

    public static final String KEY = "inventory-protection";

    public static final String DEFAULT_VERSION = "1.1.0";

    public static final String PREFIX = "operator.policies.inventory-protection";

    public InventoryProtectionPolicy {
        if (maxPausesPerStore24h < 0) {
            throw new IllegalArgumentException(PREFIX + ".max-pauses-per-store-24h must not be negative");
        }
        if (cooldownPerAd == null || cooldownPerAd.isNegative()) {
            throw new IllegalArgumentException(PREFIX + ".cooldown-per-ad must not be negative");
        }
    }

    public enum Mode {
        /** Pilot: the outcome is recorded, nothing is authorized or executed. */
        EVALUATION_ONLY,
        /** L1 (ADR-0020): a person approves one revision, and the backend executes it after a recheck. */
        EXECUTE_ON_APPROVAL
    }

    public enum Autonomy {
        /** Every action needs a person's approval. */
        L1,
        /**
         * Not available in this version (ADR-0026 point 4): readable because past evaluations record it, but the
         * policy still requires approval and the cloud profile refuses {@code l2-enabled=true}.
         */
        L2
    }

    public String ref() {
        return KEY + "@" + version;
    }

    /** The decision parameters as recorded with every evaluation, in a fixed order; {@code mode} is not one. */
    public Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("version", version);
        parameters.put("maxStockAge", maxStockAge.toString());
        parameters.put("maxAdStateAge", maxAdStateAge.toString());
        parameters.put("maxVerificationAge", maxVerificationAge.toString());
        parameters.put("maxPausesPerStore24h", Integer.toString(maxPausesPerStore24h));
        parameters.put("cooldownPerAd", cooldownPerAd.toString());
        parameters.put("autonomy", autonomy.name());
        parameters.put("l2Enabled", Boolean.toString(l2Enabled));
        return parameters;
    }
}
