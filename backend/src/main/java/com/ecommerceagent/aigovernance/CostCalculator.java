package com.ecommerceagent.aigovernance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * The conservative maximum cost of one call (ADR-0027 section 4.2): the estimated input plus a safety margin, the
 * full output limit, every additional paid unit, and no prompt cache hit. When caching may write, every input token
 * is priced at the higher of the input and cache write prices. Rounded up, never down.
 */
final class CostCalculator {

    static final int SCALE = 8;
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /** The reserved amount and the token limits it covers, or the reason the call cannot be bounded. */
    record MaxCost(BigDecimal amount, long maxInputTokens, long maxOutputTokens) {
    }

    private CostCalculator() {
    }

    static Optional<String> unboundedReason(AiPrice price, AiCallEstimate estimate) {
        if (estimate.promptCaching() && price.cacheWritePerMillion() == null) {
            return Optional.of("CACHE_NOT_PRICED");
        }
        for (Map.Entry<String, Long> unit : estimate.extraUnits().entrySet()) {
            if (unit.getValue() > 0 && !price.extras().containsKey(unit.getKey())) {
                return Optional.of("FEATURE_NOT_PRICED");
            }
        }
        return Optional.empty();
    }

    static MaxCost maxCost(AiPrice price, AiCallEstimate estimate, BigDecimal inputTokenMargin) {
        if (unboundedReason(price, estimate).isPresent()) {
            throw new IllegalArgumentException("The call's cost cannot be bounded with this price");
        }
        long maxInput = BigDecimal.valueOf(estimate.estimatedInputTokens())
                .multiply(BigDecimal.ONE.add(inputTokenMargin))
                .setScale(0, RoundingMode.CEILING)
                .longValueExact();
        BigDecimal inputPrice = price.inputPerMillion();
        if (estimate.promptCaching()) {
            inputPrice = inputPrice.max(price.cacheWritePerMillion());
        }
        BigDecimal tokens = inputPrice.multiply(BigDecimal.valueOf(maxInput))
                .add(price.outputPerMillion().multiply(BigDecimal.valueOf(estimate.maxOutputTokens())))
                .divide(MILLION, SCALE, RoundingMode.CEILING);
        BigDecimal extras = BigDecimal.ZERO;
        for (Map.Entry<String, Long> unit : estimate.extraUnits().entrySet()) {
            if (unit.getValue() > 0) {
                extras = extras.add(price.extras().get(unit.getKey()).multiply(BigDecimal.valueOf(unit.getValue())));
            }
        }
        BigDecimal amount = tokens.add(extras).setScale(SCALE, RoundingMode.CEILING);
        if (amount.signum() <= 0) {
            // A free price would reserve nothing and so bound nothing; one unit of the scale keeps it visible.
            amount = BigDecimal.ONE.movePointLeft(SCALE);
        }
        return new MaxCost(amount, maxInput, estimate.maxOutputTokens());
    }
}
