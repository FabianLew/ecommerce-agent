package com.ecommerceagent.aigovernance;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A closed reservation: the cost recorded in full and how far it went above the reservation. {@code outcome} is
 * {@code SETTLED} or {@code RECONCILED_<outcome>}; {@code repeated} is true when the same result had already been
 * recorded and nothing was charged again.
 */
public record Settlement(String outcome, BigDecimal cost, BigDecimal overrun, boolean repeated) {

    public Settlement {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(overrun, "overrun");
    }

    public boolean overran() {
        return overrun.signum() > 0;
    }
}
