package com.ecommerceagent.intelligence.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

import com.ecommerceagent.intelligence.model.ModelId;

/** A versioned price list (document 02 §3): USD per million input and output tokens, by model. */
public record PriceList(String version, Map<ModelId, Price> prices) {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    public PriceList {
        prices = Map.copyOf(prices);
    }

    public Optional<Price> price(ModelId model) {
        return Optional.ofNullable(prices.get(model));
    }

    public record Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {

        public BigDecimal cost(long inputTokens, long outputTokens) {
            return inputPerMillion.multiply(BigDecimal.valueOf(inputTokens))
                    .add(outputPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
                    .divide(MILLION, 6, RoundingMode.UP);
        }
    }
}
