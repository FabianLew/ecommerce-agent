package com.ecommerceagent.aigovernance;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * The conservative maximum cost of a call from the price catalog, without reserving anything (ADR-0027 section 9).
 * It uses the same price and the same calculation as a reservation, so a run's own cost limit and the budgets agree.
 */
public sealed interface CostQuote {

    record Priced(BigDecimal amount, String currency, UUID priceId, String priceVersion) implements CostQuote {

        public Priced {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(currency, "currency");
        }
    }

    /** The call cannot be priced: {@code NOT_PRICED}, {@code PRICE_UNVERIFIED}, {@code PRICE_STALE}, and so on. */
    record Unpriced(String reason) implements CostQuote {

        public Unpriced {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
