package com.ecommerceagent.automation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;

import com.ecommerceagent.automation.execution.ExecutionSettings;
import com.ecommerceagent.automation.guardian.GuardianSettings;
import org.junit.jupiter.api.Test;

/** ADR-0020 point 2: the application does not start with a TTL that is not positive. */
class SettingsValidationTest {

    @Test
    void aProposalTtlMustBePositive() {
        assertThatThrownBy(() -> new GuardianSettings(Duration.ZERO, Duration.ofDays(7), Duration.ofMinutes(15), 3, 5,
                new BigDecimal("0.5"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("proposal-ttl");
        assertThatThrownBy(() -> new GuardianSettings(Duration.ofHours(-1), Duration.ofDays(7), Duration.ofMinutes(15),
                3, 5, new BigDecimal("0.5"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAuthorizationTtlMustBePositive() {
        assertThatThrownBy(() -> new ExecutionSettings(true, Duration.ZERO, Duration.ofMinutes(2))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authorization-ttl");
    }
}
