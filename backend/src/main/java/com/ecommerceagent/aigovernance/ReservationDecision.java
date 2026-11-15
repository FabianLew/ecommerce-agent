package com.ecommerceagent.aigovernance;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** The governor's answer: a reservation that admits the call, or a denial. A denied call is never sent. */
public sealed interface ReservationDecision {

    /**
     * The call is admitted up to {@code amount}. {@code repeated} is true when the same idempotency key was reserved
     * before; {@code state} is then that reservation's current state.
     */
    record Reserved(UUID reservationId, BigDecimal amount, String currency, UUID priceId, String priceVersion,
            long maxInputTokens, long maxOutputTokens, boolean repeated, String state)
            implements ReservationDecision {

        public Reserved {
            Objects.requireNonNull(reservationId, "reservationId");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * No call. Reasons: {@code NOT_PRICED}, {@code PRICE_UNVERIFIED}, {@code PRICE_STALE},
     * {@code PRICE_NOT_CURRENT}, {@code CACHE_NOT_PRICED}, {@code FEATURE_NOT_PRICED}, {@code NO_PLATFORM_BUDGET},
     * {@code NO_STORE_BUDGET}, {@code CURRENCY_MISMATCH:<level>}, {@code BUDGET_EXHAUSTED:<level>:<period>}.
     */
    record Denied(String reason) implements ReservationDecision {

        public Denied {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
