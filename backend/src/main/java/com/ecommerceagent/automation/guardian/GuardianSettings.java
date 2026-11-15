package com.ecommerceagent.automation.guardian;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits of an Inventory Guardian episode (ADR-0012 point 6). They are copied into the workflow input when the
 * episode starts, so the workflow stays deterministic.
 */
@ConfigurationProperties("operator.inventory-guardian.workflow")
public record GuardianSettings(
        @DefaultValue("24h") Duration proposalTtl,
        @DefaultValue("7d") Duration episodeTimeout,
        @DefaultValue("15m") Duration dataRetryDelay,
        @DefaultValue("3") int maxDataRetries,
        @DefaultValue("5") int maxReanalyses,
        @DefaultValue("0.5") BigDecimal significantCoverChange) {

    /** Refuses to start with a proposal TTL that would make every proposal expire at once (ADR-0020 point 2). */
    public GuardianSettings {
        if (proposalTtl == null || proposalTtl.isNegative() || proposalTtl.isZero()) {
            throw new IllegalArgumentException(
                    "operator.inventory-guardian.workflow.proposal-ttl must be positive, was " + proposalTtl);
        }
    }

    public GuardianInput input(String opportunityId, String variantId) {
        return new GuardianInput(opportunityId, variantId, proposalTtl.toSeconds(), episodeTimeout.toSeconds(),
                dataRetryDelay.toSeconds(), maxDataRetries, maxReanalyses, significantCoverChange.toPlainString());
    }
}
