package com.ecommerceagent.aigovernance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-0027 section 4.2: the reserved maximum never assumes a cache hit, includes every paid unit, rounds up. */
class CostCalculatorTest {

    private static AiPrice price(String cacheWrite, String cacheRead, Map<String, BigDecimal> extras) {
        return new AiPrice(UUID.randomUUID(), "anthropic", "m", "global", "v1", "USD", new BigDecimal("3.00"),
                new BigDecimal("15.00"), cacheWrite == null ? null : new BigDecimal(cacheWrite),
                cacheRead == null ? null : new BigDecimal(cacheRead), extras, Instant.EPOCH, "test", Instant.EPOCH);
    }

    @Test
    void addsTheMarginToInputAndChargesTheWholeOutputLimit() {
        CostCalculator.MaxCost cost = CostCalculator.maxCost(price(null, null, Map.of()),
                AiCallEstimate.of("anthropic", "m", "global", 10_000, 1_500), new BigDecimal("0.25"));
        // 12,500 input tokens at 3.00 per million + 1,500 output tokens at 15.00 per million.
        assertThat(cost.maxInputTokens()).isEqualTo(12_500);
        assertThat(cost.amount()).isEqualByComparingTo("0.06000000");
    }

    @Test
    void roundsTokensAndMoneyUp() {
        CostCalculator.MaxCost cost = CostCalculator.maxCost(price(null, null, Map.of()),
                AiCallEstimate.of("anthropic", "m", "global", 3, 1), new BigDecimal("0.10"));
        assertThat(cost.maxInputTokens()).isEqualTo(4);
        // 4 * 3.00 + 1 * 15.00 = 27 per million = 0.000027
        assertThat(cost.amount()).isEqualByComparingTo("0.00002700");
        CostCalculator.MaxCost tiny = CostCalculator.maxCost(
                new AiPrice(UUID.randomUUID(), "a", "m", "global", "v", "USD", new BigDecimal("0.001"),
                        BigDecimal.ZERO, null, null, Map.of(), Instant.EPOCH, "t", Instant.EPOCH),
                AiCallEstimate.of("a", "m", "global", 1, 1), BigDecimal.ZERO);
        assertThat(tiny.amount()).as("rounded up, never to zero").isEqualByComparingTo("0.00000001");
    }

    @Test
    void cachingPricesInputAtTheCacheWritePriceAndNeverAssumesAHit() {
        AiCallEstimate cached = new AiCallEstimate("anthropic", "m", "global", 1_000_000, 1, true, Map.of());
        CostCalculator.MaxCost cost = CostCalculator.maxCost(price("3.75", "0.30", Map.of()), cached, BigDecimal.ZERO);
        assertThat(cost.amount()).isEqualByComparingTo("3.75001500");
        assertThat(CostCalculator.unboundedReason(price(null, null, Map.of()), cached)).contains("CACHE_NOT_PRICED");
    }

    @Test
    void chargesAdditionalPaidUnitsAndRefusesUnpricedOnes() {
        AiCallEstimate search = new AiCallEstimate("anthropic", "m", "global", 1_000_000, 1, false,
                Map.of("web_search", 3L));
        CostCalculator.MaxCost cost = CostCalculator.maxCost(
                price(null, null, Map.of("web_search", new BigDecimal("0.01"))), search, BigDecimal.ZERO);
        assertThat(cost.amount()).isEqualByComparingTo("3.03001500");
        assertThat(CostCalculator.unboundedReason(price(null, null, Map.of()), search)).contains("FEATURE_NOT_PRICED");
        assertThatThrownBy(() -> CostCalculator.maxCost(price(null, null, Map.of()), search, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
