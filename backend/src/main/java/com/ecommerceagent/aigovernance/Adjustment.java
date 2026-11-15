package com.ecommerceagent.aigovernance;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A correction of a conservative reconciliation: {@code outcome} is {@code CORRECTED_NOT_CHARGED} or
 * {@code CORRECTED_CHARGED}, {@code cost} the corrected cost and {@code delta} the change of every charged balance
 * (negative when money goes back to the budgets). {@code repeated} is true when the same correction had already been
 * recorded and nothing moved again.
 */
public record Adjustment(String outcome, BigDecimal cost, BigDecimal delta, boolean repeated) {

    public Adjustment {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(delta, "delta");
    }
}
