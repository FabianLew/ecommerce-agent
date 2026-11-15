package com.ecommerceagent.intelligence.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A provider failure after governance admitted the call. {@code possiblyCharged} is true when the request may have
 * reached the provider and its result is unknown: the reservation stays {@code UNKNOWN} and counts with
 * {@code recordedCost}, the full reserved amount. It is false when the provider confirmed no charge.
 */
public class GovernedModelFailure extends ModelFailure {

    private static final long serialVersionUID = 1L;

    private final boolean possiblyCharged;
    private final BigDecimal recordedCost;

    public GovernedModelFailure(ModelFailure failure, boolean possiblyCharged, BigDecimal recordedCost) {
        super(failure.type(), failure.getMessage(), failure.retryAfter(), failure.providerRequestId(),
                failure.confirmedNotCharged(), failure);
        this.possiblyCharged = possiblyCharged;
        this.recordedCost = Objects.requireNonNull(recordedCost, "recordedCost");
    }

    public boolean possiblyCharged() {
        return possiblyCharged;
    }

    public BigDecimal recordedCost() {
        return recordedCost;
    }
}
