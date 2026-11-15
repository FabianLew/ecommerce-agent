package com.ecommerceagent.intelligence.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A model turn admitted and recorded by governance. {@code cost} is the settled cost, or the full reserved amount
 * when the provider reported no usage ({@code settled} false: the reservation stays {@code UNKNOWN}).
 */
public record GovernedTurn(ModelTurn turn, UUID reservationId, BigDecimal cost, String currency, boolean settled) {

    public GovernedTurn {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(currency, "currency");
    }
}
