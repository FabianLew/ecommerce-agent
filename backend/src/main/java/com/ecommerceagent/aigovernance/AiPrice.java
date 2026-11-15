package com.ecommerceagent.aigovernance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One version of a model's price in one region (ADR-0027 section 9): token prices per million, optional prompt cache
 * prices, additional paid components per unit, when it takes effect, its source and when it was last verified.
 */
public record AiPrice(UUID id, String provider, String model, String region, String priceVersion, String currency,
        BigDecimal inputPerMillion, BigDecimal outputPerMillion, BigDecimal cacheWritePerMillion,
        BigDecimal cacheReadPerMillion, Map<String, BigDecimal> extras, Instant effectiveFrom, String source,
        Instant verifiedAt) {

    public AiPrice {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(inputPerMillion, "inputPerMillion");
        Objects.requireNonNull(outputPerMillion, "outputPerMillion");
        extras = Map.copyOf(extras);
    }
}
