package com.ecommerceagent.intelligence.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** ADR-0027 section 5: the run policy of the Inventory Agent and the limits it hands to the runtime. */
class InventoryAgentRunPolicyTest {

    @Test
    void theDefaultsAreTheLimitsThatWereCodeBefore() {
        InventoryAgentRunPolicy policy = InventoryAgentRunPolicy.defaults();

        assertThat(policy.version()).isEqualTo("inventory-agent-run/1");
        var limits = policy.limits();
        assertThat(limits.policy()).isEqualTo("inventory-agent-run/1");
        assertThat(limits.maxModelCalls()).isEqualTo(5);
        assertThat(limits.maxToolCalls()).isEqualTo(8);
        assertThat(limits.maxWallTime()).isEqualTo(Duration.ofSeconds(60));
        assertThat(limits.maxInputTokens()).isEqualTo(12_000);
        assertThat(limits.maxOutputTokensPerCall()).isEqualTo(1_500);
        assertThat(limits.maxOutputTokensTotal()).isEqualTo(4_000);
        assertThat(limits.maxEstimatedCostUsd()).isEqualByComparingTo("0.10");
        assertThat(limits.maxOutputRepairCalls()).isEqualTo(1);
        assertThat(policy.maxAttempts()).isEqualTo(2);
        assertThat(limits.recorded()).containsEntry("maxEstimatedCostUsd", "0.1").containsEntry("maxWallTime", "PT1M");
    }

    @Test
    void anOverrideBindsWithItsOwnVersion() {
        InventoryAgentRunPolicy policy = bind(Map.of(
                InventoryAgentRunPolicy.PREFIX + ".version", "inventory-agent-run/2",
                InventoryAgentRunPolicy.PREFIX + ".max-run-cost-usd", "0.08",
                InventoryAgentRunPolicy.PREFIX + ".max-model-calls", "3"));

        assertThat(policy.limits().policy()).isEqualTo("inventory-agent-run/2");
        assertThat(policy.limits().maxEstimatedCostUsd()).isEqualByComparingTo(new BigDecimal("0.08"));
        assertThat(policy.limits().maxModelCalls()).isEqualTo(3);
    }

    @Test
    void anInconsistentPolicyIsRefusedAtStartUp() {
        assertThatThrownBy(() -> bind(Map.of(InventoryAgentRunPolicy.PREFIX + ".version", "v2")))
                .isInstanceOf(BindException.class).rootCause().hasMessageContaining("inventory-agent-run/N");
        assertThatThrownBy(() -> bind(Map.of(InventoryAgentRunPolicy.PREFIX + ".max-output-tokens-per-call", "5000")))
                .isInstanceOf(BindException.class).rootCause()
                .hasMessageContaining("must not be below the output limit of one call");
        assertThatThrownBy(() -> bind(Map.of(InventoryAgentRunPolicy.PREFIX + ".max-run-cost-usd", "0")))
                .isInstanceOf(BindException.class).rootCause().hasMessageContaining("cost limit must be positive");
        assertThatThrownBy(() -> bind(Map.of(InventoryAgentRunPolicy.PREFIX + ".max-model-calls", "0")))
                .isInstanceOf(BindException.class).rootCause().hasMessageContaining("out of range");
        assertThatThrownBy(() -> bind(Map.of(InventoryAgentRunPolicy.PREFIX + ".max-run-duration", "0s")))
                .isInstanceOf(BindException.class).rootCause().hasMessageContaining("wall time must be positive");
        assertThatThrownBy(() -> bind(Map.of(InventoryAgentRunPolicy.PREFIX + ".max-attempts", "0")))
                .isInstanceOf(BindException.class).rootCause().hasMessageContaining("max-attempts");
    }

    private static InventoryAgentRunPolicy bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate(InventoryAgentRunPolicy.PREFIX, InventoryAgentRunPolicy.class);
    }
}
