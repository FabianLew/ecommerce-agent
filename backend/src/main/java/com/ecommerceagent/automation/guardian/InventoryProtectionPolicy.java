package com.ecommerceagent.automation.guardian;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.modulith.NamedInterface;

/**
 * Parameters of policy {@code inventory-protection} (ADR-0012 point 5), with an explicit version recorded in
 * every evaluation. Per-store configuration arrives with the policy screen.
 */
@ConfigurationProperties("operator.policies.inventory-protection")
@NamedInterface("guardian")
public record InventoryProtectionPolicy(
        @DefaultValue("1.0.0") String version,
        @DefaultValue("EVALUATION_ONLY") Mode mode,
        @DefaultValue("120s") Duration maxStockAge,
        @DefaultValue("60s") Duration maxAdStateAge,
        @DefaultValue("24h") Duration maxVerificationAge,
        @DefaultValue("L1") Autonomy autonomy,
        @DefaultValue("false") boolean l2Enabled) {

    public static final String KEY = "inventory-protection";

    public enum Mode {
        /** Pilot: the outcome is recorded, nothing is authorized or executed. */
        EVALUATION_ONLY
    }

    public enum Autonomy {
        /** Every action needs a person's approval. */
        L1,
        /** Actions the policy allows run without approval; only with {@code l2Enabled}. */
        L2
    }

    public String ref() {
        return KEY + "@" + version;
    }
}
